package com.orderflow.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.e2e.support.E2eHttp;
import com.orderflow.e2e.support.E2eInfrastructure;
import com.orderflow.e2e.support.E2eJwt;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova de ponta a ponta da saga de reserva de estoque (Success Criteria 2, 3, 4 e 5 do ROADMAP,
 * D-68) entre os dois contextos Spring reais expostos por {@link E2eInfrastructure} — o pedido só
 * muda de estado pelas mensagens reais trocadas entre order-service e inventory-service via
 * LocalStack; nenhum dublê de teste, nenhum resultado forjado, nenhuma escrita direta de status de
 * pedido no banco (TEST-03).
 *
 * <p>Caminho de falha escrito ANTES do caminho de sucesso (D-68, Success Criteria 3) — esta classe
 * nasce só com os dois cenários de falha (estoque insuficiente e produto sem linha de estoque); o
 * caminho de sucesso e a idempotência da republicação chegam depois, na Task 2 deste plano.
 */
class OrderReservationSagaE2EIT {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String INVENTORY_COMMANDS_QUEUE = "inventory-commands-queue";

    @Test
    void orderExceedingAvailableStockEndsCancelledWithInsufficientStockAndNoReservation() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 2);

        E2eHttp.Response created = createOrder(companyId, productId, 5);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("RESERVING");
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response terminal = awaitOrderStatus(orderId, companyId, "CANCELLED");
        assertThat(terminal.text("cancellationCode")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(terminal.text("cancellationReason"))
                .contains("disponível 2")
                .contains("solicitado 5");

        E2eHttp.Response stock = getStock(productId);
        assertThat(stock.body().get("quantityReserved").asInt()).isZero();
    }

    @Test
    void orderForProductWithoutAnyStockLineEndsCancelledWithProductNotStocked() {
        // Deliberadamente NENHUM PUT /inventory/{productId} — o produto existe no catálogo (stub)
        // mas nunca teve estoque cadastrado (D-58).
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));

        E2eHttp.Response created = createOrder(companyId, productId, 1);
        assertThat(created.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response terminal = awaitOrderStatus(orderId, companyId, "CANCELLED");
        assertThat(terminal.text("cancellationCode")).isEqualTo("PRODUCT_NOT_STOCKED");
    }

    // ---- Caminho feliz e idempotência (Task 2) ----

    @Test
    void orderWithSufficientStockEndsConfirmedWithStockReservedInInventory() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);

        E2eHttp.Response created = createOrder(companyId, productId, 3);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("RESERVING");
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response confirmedResponse = awaitOrderStatus(orderId, companyId, "CONFIRMED");
        assertThat(confirmedResponse.text("confirmedAt")).isNotNull();
        assertThat(confirmedResponse.text("cancellationCode")).isNull();

        E2eHttp.Response stock = getStock(productId);
        assertThat(stock.body().get("quantityOnHand").asInt()).isEqualTo(10);
        assertThat(stock.body().get("quantityReserved").asInt()).isEqualTo(3);
        assertThat(stock.body().get("quantityAvailable").asInt()).isEqualTo(7);
    }

    /**
     * Critério 4 do ROADMAP de ponta a ponta: reenviar o MESMO comando {@code ReserveStock} de um
     * pedido já confirmado (mesmo {@code orderId}/{@code reservationId}/itens, {@code eventId}
     * novo) direto na fila real não reserva de novo — {@code InventoryService#reserveAll} responde
     * com o replay idempotente (D-65), gravando um SEGUNDO {@code StockReserved} no outbox do
     * inventory-service sem tocar em {@code quantity_reserved}, e o pedido permanece com o MESMO
     * {@code confirmedAt} (a mensagem nunca chega a mudar o estado do pedido — ele já não está mais
     * em RESERVING).
     */
    @Test
    void republishingSameReserveStockAfterConfirmedDoesNotReserveAgain() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);

        E2eHttp.Response created = createOrder(companyId, productId, 3);
        assertThat(created.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response firstConfirmation = awaitOrderStatus(orderId, companyId, "CONFIRMED");
        String confirmedAt = firstConfirmation.text("confirmedAt");
        assertThat(confirmedAt).isNotNull();

        Awaitility.await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(countOutboxEvents("StockReserved", orderId)).isEqualTo(1));

        republishReserveStock(orderId, productId, 3);

        Awaitility.await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(countOutboxEvents("StockReserved", orderId)).isEqualTo(2));

        E2eHttp.Response afterReplay = E2eHttp.get(
                E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId, E2eJwt.buyerToken(companyId));
        assertThat(afterReplay.status()).isEqualTo(200);
        assertThat(afterReplay.text("status")).isEqualTo("CONFIRMED");
        assertThat(afterReplay.text("confirmedAt")).isEqualTo(confirmedAt);

        E2eHttp.Response stock = getStock(productId);
        assertThat(stock.body().get("quantityReserved").asInt()).isEqualTo(3);
    }

    /**
     * Os dois pontos de entrada da saga (D-48) de ponta a ponta: um pedido acima do limite de
     * crédito fica {@code PENDING_APPROVAL}, é aprovado manualmente pelo vendedor, e também termina
     * confirmado com o estoque reservado.
     */
    @Test
    void pendingApprovalOrderApprovedManuallyAlsoEndsConfirmedWithStockReserved() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("50.00"));
        putStock(productId, 10);

        E2eHttp.Response created = createOrder(companyId, productId, 6);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("PENDING_APPROVAL");
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response approved = E2eHttp.postJson(
                E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId + "/approve",
                E2eJwt.sellerAdminToken(),
                Map.of());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.text("status")).isEqualTo("RESERVING");

        awaitOrderStatus(orderId, companyId, "CONFIRMED");

        E2eHttp.Response stock = getStock(productId);
        assertThat(stock.body().get("quantityReserved").asInt()).isEqualTo(6);
    }

    // ---- Suporte compartilhado pelos testes desta classe (Task 1 e Task 2) ----

    private static UUID registerProduct(BigDecimal price) {
        UUID productId = UUID.randomUUID();
        E2eInfrastructure.stub().registerProduct(
                productId, "SKU-" + productId.toString().substring(0, 8), "Produto E2E", price, "ACTIVE");
        return productId;
    }

    private static void registerCreditLimit(UUID companyId, BigDecimal creditLimit) {
        E2eInfrastructure.stub().registerCreditLimit(companyId, creditLimit);
    }

    private static E2eHttp.Response putStock(UUID productId, int quantityOnHand) {
        String sellerToken = E2eJwt.sellerAdminToken();
        E2eHttp.Response response = E2eHttp.put(
                E2eInfrastructure.inventoryBaseUrl() + "/inventory/" + productId,
                sellerToken,
                Map.of("quantityOnHand", quantityOnHand));
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    private static E2eHttp.Response getStock(UUID productId) {
        E2eHttp.Response response = E2eHttp.get(
                E2eInfrastructure.inventoryBaseUrl() + "/inventory/" + productId, E2eJwt.sellerAdminToken());
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    private static E2eHttp.Response createOrder(UUID companyId, UUID productId, int quantity) {
        String buyerToken = E2eJwt.buyerToken(companyId);
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));
        return E2eHttp.postJson(E2eInfrastructure.orderBaseUrl() + "/orders", buyerToken, body);
    }

    private static E2eHttp.Response awaitOrderStatus(UUID orderId, UUID companyId, String... acceptableStatuses) {
        String buyerToken = E2eJwt.buyerToken(companyId);
        AtomicReference<E2eHttp.Response> lastResponse = new AtomicReference<>();
        Awaitility.await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    E2eHttp.Response response = E2eHttp.get(
                            E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId, buyerToken);
                    lastResponse.set(response);
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.text("status")).isIn((Object[]) acceptableStatuses);
                });
        return lastResponse.get();
    }

    /** Só leitura no {@code outbox_event} do inventory-service (TEST-03 — nenhuma escrita direta). */
    private static long countOutboxEvents(String eventType, UUID orderId) {
        Long count = E2eInfrastructure.jdbc().queryForObject(
                "SELECT COUNT(*) FROM inventory.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Long.class, eventType, orderId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Monta o JSON do comando {@code ReserveStock} (SAGA_MESSAGE_CONTRACT) com o {@code
     * ObjectMapper} a partir dos dados do pedido já criado — mesmo {@code orderId}/{@code
     * reservationId}/itens, {@code eventId} novo — e envia direto na {@code
     * inventory-commands-queue} pelo {@link SqsAsyncClient} exposto por {@link
     * E2eInfrastructure#inventorySqs()}, exatamente como o relay do outbox do order-service faria.
     */
    private static void republishReserveStock(UUID orderId, UUID productId, int quantity) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("eventId", UUID.randomUUID().toString());
        command.put("eventType", "ReserveStock");
        command.put("occurredAt", Instant.now().toString());
        command.put("orderId", orderId.toString());
        command.put("reservationId", orderId.toString());
        command.put("items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));

        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(command);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize replayed ReserveStock command", e);
        }

        SqsAsyncClient sqsAsyncClient = E2eInfrastructure.inventorySqs();
        String queueUrl = sqsAsyncClient
                .getQueueUrl(GetQueueUrlRequest.builder().queueName(INVENTORY_COMMANDS_QUEUE).build())
                .join()
                .queueUrl();
        sqsAsyncClient.sendMessage(
                SendMessageRequest.builder().queueUrl(queueUrl).messageBody(payload).build()).join();
    }
}

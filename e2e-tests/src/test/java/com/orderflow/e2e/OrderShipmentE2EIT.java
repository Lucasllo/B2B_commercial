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
 * Prova de ponta a ponta da expedição (ORD-10, D-86) entre os dois contextos Spring reais expostos
 * por {@link E2eInfrastructure} — order-service e inventory-service conversando pela {@code
 * inventory-commands-queue} real do LocalStack: expedir um pedido CONFIRMED faz o inventory baixar
 * {@code quantityOnHand} e {@code quantityReserved} uma única vez, a republicação do {@code
 * ShipStock} não baixa de novo e a entrega não mexe no estoque. Nenhum dublê, nenhum resultado
 * forjado, nenhuma escrita direta no banco (TEST-03) — o JDBC do módulo é somente leitura.
 *
 * <p>D-86: o notification-service fica FORA deste E2E por custo e fragilidade (mais um contexto,
 * mais uma tabela DynamoDB, mais tempo de subida). A linha do tempo do pedido é provada pelos ITs
 * próprios do notification-service e do order-service (06-04/06-05) e pelo smoke da stack real
 * ({@code scripts/smoke-order-lifecycle.sh}, 06-06 Task 1), que é a única prova que junta os três
 * serviços.
 */
class OrderShipmentE2EIT {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final long REPLAY_SETTLE_MILLIS = 3_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String INVENTORY_COMMANDS_QUEUE = "inventory-commands-queue";
    private static final String TRACKING_CODE_PATTERN = "^[A-Z]{2}[0-9]{9}BR$";

    @Test
    void confirmedOrderGetsCarrierAndTrackingCodeInTheCorrespondingFormat() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);

        UUID orderId = createConfirmedOrder(companyId, productId, 3);

        E2eHttp.Response confirmed = getOrder(orderId, companyId);
        assertThat(confirmed.text("carrier")).isNotBlank();
        assertThat(confirmed.text("trackingCode")).matches(TRACKING_CODE_PATTERN);
    }

    @Test
    void shippingConfirmedOrderDecrementsOnHandAndReservedInInventoryAndMarksReservationShipped() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);
        UUID orderId = createConfirmedOrder(companyId, productId, 3);

        E2eHttp.Response beforeShip = getStock(productId);
        assertThat(beforeShip.body().get("quantityOnHand").asInt()).isEqualTo(10);
        assertThat(beforeShip.body().get("quantityReserved").asInt()).isEqualTo(3);

        E2eHttp.Response shipped = postAction(orderId, "ship");
        assertThat(shipped.status()).isEqualTo(200);
        assertThat(shipped.text("status")).isEqualTo("SHIPPED");

        awaitStock(productId, 7, 0);
        E2eHttp.Response afterShip = getStock(productId);
        assertThat(afterShip.body().get("quantityAvailable").asInt()).isEqualTo(7);
        assertThat(isReservationShipped(orderId, productId)).isTrue();
    }

    /**
     * Idempotência da baixa (D-86): reenviar o MESMO {@code ShipStock} (mesmo {@code
     * orderId}/{@code reservationId}/itens, {@code eventId} novo) direto na fila real não baixa o
     * estoque de novo — a marca {@code shipped} do livro de reservas é a guarda, não o {@code
     * eventId}.
     */
    @Test
    void republishingSameShipStockDoesNotDecrementStockAgain() throws InterruptedException {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);
        UUID orderId = createConfirmedOrder(companyId, productId, 3);

        assertThat(postAction(orderId, "ship").status()).isEqualTo(200);
        awaitStock(productId, 7, 0);

        republishShipStock(orderId, productId, 3);
        Thread.sleep(REPLAY_SETTLE_MILLIS);

        E2eHttp.Response afterReplay = getStock(productId);
        assertThat(afterReplay.body().get("quantityOnHand").asInt()).isEqualTo(7);
        assertThat(afterReplay.body().get("quantityReserved").asInt()).isZero();
    }

    @Test
    void deliveringShippedOrderDoesNotTouchStock() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);
        UUID orderId = createConfirmedOrder(companyId, productId, 3);

        assertThat(postAction(orderId, "ship").status()).isEqualTo(200);
        awaitStock(productId, 7, 0);

        E2eHttp.Response delivered = postAction(orderId, "deliver");
        assertThat(delivered.status()).isEqualTo(200);
        assertThat(delivered.text("status")).isEqualTo("DELIVERED");

        E2eHttp.Response afterDeliver = getStock(productId);
        assertThat(afterDeliver.body().get("quantityOnHand").asInt()).isEqualTo(7);
        assertThat(afterDeliver.body().get("quantityReserved").asInt()).isZero();
    }

    @Test
    void shippingOrderCancelledForInsufficientStockIsRejectedAndLeavesStockUntouched() {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 2);

        E2eHttp.Response created = createOrder(companyId, productId, 5);
        assertThat(created.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(created.text("id"));
        awaitOrderStatus(orderId, companyId, "CANCELLED");

        E2eHttp.Response ship = postAction(orderId, "ship");
        assertThat(ship.status()).isEqualTo(409);
        assertThat(ship.text("error")).isEqualTo("invalid_order_transition");

        E2eHttp.Response stock = getStock(productId);
        assertThat(stock.body().get("quantityOnHand").asInt()).isEqualTo(2);
        assertThat(stock.body().get("quantityReserved").asInt()).isZero();
    }

    // ---- Suporte compartilhado pelos testes desta classe ----

    private static UUID createConfirmedOrder(UUID companyId, UUID productId, int quantity) {
        E2eHttp.Response created = createOrder(companyId, productId, quantity);
        assertThat(created.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(created.text("id"));
        awaitOrderStatus(orderId, companyId, "CONFIRMED");
        return orderId;
    }

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
        E2eHttp.Response response = E2eHttp.put(
                E2eInfrastructure.inventoryBaseUrl() + "/inventory/" + productId,
                E2eJwt.sellerAdminToken(),
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

    private static void awaitStock(UUID productId, int expectedOnHand, int expectedReserved) {
        Awaitility.await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    E2eHttp.Response stock = getStock(productId);
                    assertThat(stock.body().get("quantityOnHand").asInt()).isEqualTo(expectedOnHand);
                    assertThat(stock.body().get("quantityReserved").asInt()).isEqualTo(expectedReserved);
                });
    }

    private static E2eHttp.Response createOrder(UUID companyId, UUID productId, int quantity) {
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));
        return E2eHttp.postJson(
                E2eInfrastructure.orderBaseUrl() + "/orders", E2eJwt.buyerToken(companyId), body);
    }

    private static E2eHttp.Response getOrder(UUID orderId, UUID companyId) {
        E2eHttp.Response response = E2eHttp.get(
                E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId, E2eJwt.buyerToken(companyId));
        assertThat(response.status()).isEqualTo(200);
        return response;
    }

    /** {@code POST /orders/{id}/ship} ou {@code /deliver} como vendedor (sem corpo relevante). */
    private static E2eHttp.Response postAction(UUID orderId, String action) {
        return E2eHttp.postJson(
                E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId + "/" + action,
                E2eJwt.sellerAdminToken(),
                Map.of());
    }

    private static E2eHttp.Response awaitOrderStatus(UUID orderId, UUID companyId, String... acceptableStatuses) {
        AtomicReference<E2eHttp.Response> lastResponse = new AtomicReference<>();
        Awaitility.await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> {
                    E2eHttp.Response response = E2eHttp.get(
                            E2eInfrastructure.orderBaseUrl() + "/orders/" + orderId,
                            E2eJwt.buyerToken(companyId));
                    lastResponse.set(response);
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.text("status")).isIn((Object[]) acceptableStatuses);
                });
        return lastResponse.get();
    }

    /** Só leitura em {@code inventory.stock_reservations} (TEST-03 — nenhuma escrita direta). */
    private static boolean isReservationShipped(UUID orderId, UUID productId) {
        Boolean shipped = E2eInfrastructure.jdbc().queryForObject(
                "SELECT shipped FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Boolean.class, orderId.toString(), productId);
        return Boolean.TRUE.equals(shipped);
    }

    /**
     * Monta o JSON do comando {@code ShipStock} (SAGA_MESSAGE_CONTRACT) com o {@code ObjectMapper}
     * — mesmo {@code orderId}/{@code reservationId}/itens do pedido já expedido, {@code eventId}
     * novo — e envia direto na {@code inventory-commands-queue} pelo {@link SqsAsyncClient} exposto
     * por {@link E2eInfrastructure#inventorySqs()}, exatamente como o relay do outbox do
     * order-service faria.
     */
    private static void republishShipStock(UUID orderId, UUID productId, int quantity) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("eventId", UUID.randomUUID().toString());
        command.put("eventType", "ShipStock");
        command.put("occurredAt", Instant.now().toString());
        command.put("orderId", orderId.toString());
        command.put("reservationId", orderId.toString());
        command.put("items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));

        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(command);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize replayed ShipStock command", e);
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

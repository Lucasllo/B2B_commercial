package com.orderflow.e2e;

import com.orderflow.e2e.support.E2eHttp;
import com.orderflow.e2e.support.E2eInfrastructure;
import com.orderflow.e2e.support.E2eJwt;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
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
}

package com.orderflow.e2e;

import com.orderflow.e2e.support.E2eHttp;
import com.orderflow.e2e.support.E2eInfrastructure;
import com.orderflow.e2e.support.E2eJwt;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segunda camada da prova do criterio 2 (D-99): o mesmo Correlation-ID vai no POST /orders,
 * aparece no log do ReservationCommandListener (inventory) e volta no log do
 * ReservationResultListener (order), e fica gravado em inventory.outbox_event.
 * O notification-service fica fora deste E2E por custo (D-86) e e coberto pelo smoke (07-11).
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdE2EIT {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    @Test
    void postedCorrelationIdReturnsOnBothListenersAndOnTheStockReservedOutboxRow(CapturedOutput output) {
        UUID productId = registerProduct(new BigDecimal("10.00"));
        UUID companyId = UUID.randomUUID();
        registerCreditLimit(companyId, new BigDecimal("100000.00"));
        putStock(productId, 10);

        String correlationId = "e2e-cid-" + UUID.randomUUID().toString().substring(0, 8);
        E2eHttp.Response created = createOrder(companyId, productId, 3, correlationId);
        assertThat(created.status()).isEqualTo(201);
        UUID orderId = UUID.fromString(created.text("id"));

        E2eHttp.Response confirmed = awaitOrderStatus(orderId, companyId, "CONFIRMED");
        assertThat(confirmed.text("status")).isEqualTo("CONFIRMED");

        String logs = output.getOut() + output.getErr();
        assertThat(lineHas(logs, "ReservationCommandListener", correlationId)).isTrue();
        assertThat(lineHas(logs, "ReservationResultListener", correlationId)).isTrue();

        List<String> stored = E2eInfrastructure.jdbc().query(
                "SELECT correlation_id FROM inventory.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                (rs, rowNum) -> rs.getString(1),
                "StockReserved",
                orderId.toString());
        assertThat(stored).containsExactly(correlationId);
    }

    private static boolean lineHas(String logs, String typeName, String correlationId) {
        String marker = "[" + correlationId + "]";
        for (String line : logs.split("\\R")) {
            if (line.contains(typeName) && line.contains(marker)) {
                return true;
            }
        }
        return false;
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

    private static void putStock(UUID productId, int quantityOnHand) {
        E2eHttp.Response response = E2eHttp.put(
                E2eInfrastructure.inventoryBaseUrl() + "/inventory/" + productId,
                E2eJwt.sellerAdminToken(),
                Map.of("quantityOnHand", quantityOnHand));
        assertThat(response.status()).isEqualTo(200);
    }

    private static E2eHttp.Response createOrder(UUID companyId, UUID productId, int quantity, String correlationId) {
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId.toString(), "quantity", quantity)));
        return E2eHttp.postJson(
                E2eInfrastructure.orderBaseUrl() + "/orders",
                E2eJwt.buyerToken(companyId),
                body,
                Map.of("X-Correlation-Id", correlationId));
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

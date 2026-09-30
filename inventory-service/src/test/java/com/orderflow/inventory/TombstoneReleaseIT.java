package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.support.SagaQueues;
import com.orderflow.inventory.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, da liberação idempotente (D-14 estendido) e da lápide
 * (D-66) — a corrida da fila SQS padrão (sem ordem garantida) entre {@code ReleaseStock} e {@code
 * ReserveStock} do mesmo pedido termina sempre com "nada reservado", em qualquer ordem de chegada.
 * {@code ReleaseStock} não gera nenhuma resposta na {@code order-events-queue}, então os testes
 * aqui verificam o efeito direto no banco (mesma técnica de {@code IdempotentReservationIT}).
 */
class TombstoneReleaseIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    private SagaQueues sagaQueues() {
        return new SagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    private void setStock(UUID productId, int quantityOnHand) throws Exception {
        String token = TestJwt.sellerAdminToken();
        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityOnHand":%d}
                                """.formatted(quantityOnHand)))
                .andExpect(status().isOk());
    }

    private String reserveStockCommand(UUID orderId, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","items":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderId, itemsJson);
    }

    private String releaseStockCommand(UUID orderId, String reason, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","reason":"%s","items":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderId, reason, itemsJson);
    }

    private String itemJson(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private int quantityReserved(UUID productId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT quantity_reserved FROM inventory.inventory WHERE product_id = ?",
                Integer.class, productId);
        return value == null ? 0 : value;
    }

    private int stockReservationCount(String reservationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.stock_reservations WHERE reservation_id = ?",
                Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private int liveReservationCount(String reservationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.stock_reservations WHERE reservation_id = ? AND released = false",
                Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private boolean reservationReleased(String reservationId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT released FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Boolean.class, reservationId, productId);
    }

    private Instant reservationReleasedAt(String reservationId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT released_at FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Instant.class, reservationId, productId);
    }

    private int reservationQuantity(String reservationId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Integer.class, reservationId, productId);
    }

    // -----------------------------------------------------------------------------------------
    // ReleaseStock devolve uma reserva EXISTENTE, idempotente.
    // -----------------------------------------------------------------------------------------

    @Test
    void releaseAfterReserveReturnsQuantityAndMarksRowReleasedThenIsIdempotentOnRepeat() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);

        sagaQueues().sendCommand(reserveStockCommand(orderId, "[" + itemJson(productId, 3) + "]"));
        List<JsonNode> reserved = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(reserved.get(0).get("eventType").asText()).isEqualTo("StockReserved");
        assertThat(quantityReserved(productId)).isEqualTo(3);

        sagaQueues().sendCommand(releaseStockCommand(orderId, "RESERVATION_TIMEOUT", "[" + itemJson(productId, 3) + "]"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(quantityReserved(productId)).isZero();
            assertThat(reservationReleased(orderId.toString(), productId)).isTrue();
            assertThat(reservationReleasedAt(orderId.toString(), productId)).isNotNull();
        });

        // Um segundo ReleaseStock igual — no-op idempotente, nao muda nada.
        sagaQueues().sendCommand(releaseStockCommand(orderId, "RESERVATION_TIMEOUT", "[" + itemJson(productId, 3) + "]"));
        Thread.sleep(3000);
        assertThat(quantityReserved(productId)).isZero();
        assertThat(stockReservationCount(orderId.toString())).isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------
    // ReleaseStock ANTES do ReserveStock — lápide, inclusive para produto sem linha de estoque.
    // -----------------------------------------------------------------------------------------

    @Test
    void releaseBeforeReserveWritesTombstonesEvenForProductWithoutStockLineAndLaterReserveIsCancelled() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID stockedProduct = UUID.randomUUID();
        UUID neverStockedProduct = UUID.randomUUID();
        setStock(stockedProduct, 10);

        String items = "[" + itemJson(stockedProduct, 2) + "," + itemJson(neverStockedProduct, 4) + "]";
        sagaQueues().sendCommand(releaseStockCommand(orderId, "RESERVATION_TIMEOUT", items));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(stockReservationCount(orderId.toString())).isEqualTo(2));

        assertThat(reservationReleased(orderId.toString(), stockedProduct)).isTrue();
        assertThat(reservationQuantity(orderId.toString(), stockedProduct)).isEqualTo(2);
        assertThat(reservationReleased(orderId.toString(), neverStockedProduct)).isTrue();
        assertThat(reservationQuantity(orderId.toString(), neverStockedProduct)).isEqualTo(4);
        assertThat(quantityReserved(stockedProduct)).isZero();

        sagaQueues().sendCommand(reserveStockCommand(orderId, items));
        List<JsonNode> results = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(results).hasSize(1);
        JsonNode event = results.get(0);
        assertThat(event.get("eventType").asText()).isEqualTo("StockReservationFailed");
        assertThat(event.get("reasonCode").asText()).isEqualTo("RESERVATION_CANCELLED");
        assertThat(event.get("failures")).isEmpty();
        assertThat(quantityReserved(stockedProduct)).isZero();
    }

    // -----------------------------------------------------------------------------------------
    // ReleaseStock e ReserveStock do mesmo pedido chegando JUNTOS — sempre "nada reservado".
    // -----------------------------------------------------------------------------------------

    @Test
    void releaseAndReserveSentTogetherAlwaysEndWithUnchangedQuantityAndNoLiveReservation() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID productId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            setStock(productId, 10);
            int before = quantityReserved(productId);

            String items = "[" + itemJson(productId, 3) + "]";
            sagaQueues().sendCommand(releaseStockCommand(orderId, "RESERVATION_TIMEOUT", items));
            sagaQueues().sendCommand(reserveStockCommand(orderId, items));

            // Awaitility (nao Thread.sleep fixo): a resolucao da corrida pode exigir reexecucoes
            // do @Retryable de um dos dois lados (conflito de unicidade/versao) antes de convergir.
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                assertThat(quantityReserved(productId)).isEqualTo(before);
                assertThat(liveReservationCount(orderId.toString())).isZero();
            });
        }
    }

    // -----------------------------------------------------------------------------------------
    // DELETE REST mantém D-14 (REST_RESERVATION_ENDPOINTS=kept) — nunca grava lápide.
    // -----------------------------------------------------------------------------------------

    @Test
    void deleteRestForNeverExistingReservationIdReturns200AndWritesNoRow() throws Exception {
        UUID productId = UUID.randomUUID();
        setStock(productId, 5);

        mockMvc.perform(delete("/inventory/" + productId + "/reservations/nunca-existiu")
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk());

        assertThat(stockReservationCount("nunca-existiu")).isZero();
    }
}

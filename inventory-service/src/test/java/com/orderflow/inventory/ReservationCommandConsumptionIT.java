package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.support.SagaQueues;
import com.orderflow.inventory.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, do consumo do comando {@code ReserveStock} da
 * {@code inventory-commands-queue} pelo {@code ReservationCommandListener} (Task 1 e Task 2 deste
 * plano). Caminhos de FALHA (Task 1) vêm antes do caminho feliz (Task 2, D-68/Success Criteria 3) —
 * ambos no mesmo arquivo, mas o caminho feliz só existe a partir do commit da Task 2.
 *
 * <p>Esta suíte não tem consumidor da {@code order-events-queue} (mesmo padrão de {@code
 * ReservationCommandPublishingIT} do order-service) — {@link SagaQueues} lê e apaga as mensagens
 * que encontra, guardando só as do {@code orderId} que interessa.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReservationCommandConsumptionIT extends AbstractIntegrationTest {

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

    private String itemJson(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private int outboxRowCount(UUID orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.outbox_event WHERE aggregate_id = ?",
                Integer.class, orderId.toString());
        return count == null ? 0 : count;
    }

    private int stockReservationCount(String reservationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.stock_reservations WHERE reservation_id = ?",
                Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private Instant outboxPublishedAt(UUID orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM inventory.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Instant.class, orderId.toString(), eventType);
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 — caminhos de falha (D-68/Success Criteria 3: escritos antes do caminho feliz).
    // -----------------------------------------------------------------------------------------

    @Test
    void insufficientStockProducesFailureWithReasonAndDetailAndReservesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 2);

        sagaQueues().sendCommand(reserveStockCommand(orderId, "[" + itemJson(productId, 5) + "]"));

        List<JsonNode> results = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(results).hasSize(1);
        JsonNode event = results.get(0);
        assertThat(event.get("eventType").asText()).isEqualTo("StockReservationFailed");
        assertThat(event.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(event.get("reservationId").asText()).isEqualTo(orderId.toString());
        assertThat(event.get("reasonCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(event.get("failures")).hasSize(1);
        JsonNode failure = event.get("failures").get(0);
        assertThat(failure.get("productId").asText()).isEqualTo(productId.toString());
        assertThat(failure.get("requested").asInt()).isEqualTo(5);
        assertThat(failure.get("available").asInt()).isEqualTo(2);

        String getResponse = mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(getResponse).contains("\"quantityOnHand\":2");
        assertThat(stockReservationCount(orderId.toString())).isZero();
        assertThat(outboxPublishedAt(orderId, "StockReservationFailed")).isNotNull();
    }

    @Test
    void productWithoutStockLineProducesProductNotStockedAndMessageIsConsumedOnce() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        sagaQueues().sendCommand(reserveStockCommand(orderId, "[" + itemJson(productId, 3) + "]"));

        List<JsonNode> results = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(results).hasSize(1);
        JsonNode event = results.get(0);
        assertThat(event.get("reasonCode").asText()).isEqualTo("PRODUCT_NOT_STOCKED");
        JsonNode failure = event.get("failures").get(0);
        assertThat(failure.get("productId").asText()).isEqualTo(productId.toString());
        assertThat(failure.get("requested").asInt()).isEqualTo(3);
        assertThat(failure.get("available").asInt()).isEqualTo(0);

        List<JsonNode> extra = sagaQueues().drainResultsForOrderDuring(orderId, Duration.ofSeconds(5));
        assertThat(extra).isEmpty();
    }

    @Test
    void twoItemsOnlyOneInsufficientFailsListingOnlyThatProduct() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID sufficientProduct = UUID.randomUUID();
        UUID insufficientProduct = UUID.randomUUID();
        setStock(sufficientProduct, 10);
        setStock(insufficientProduct, 1);

        String items = "[" + itemJson(sufficientProduct, 3) + "," + itemJson(insufficientProduct, 2) + "]";
        sagaQueues().sendCommand(reserveStockCommand(orderId, items));

        List<JsonNode> results = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(results).hasSize(1);
        JsonNode event = results.get(0);
        assertThat(event.get("reasonCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(event.get("failures")).hasSize(1);
        assertThat(event.get("failures").get(0).get("productId").asText()).isEqualTo(insufficientProduct.toString());

        mockMvc.perform(get("/inventory/" + sufficientProduct)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(0));
        assertThat(stockReservationCount(orderId.toString())).isZero();
    }

    @Test
    void oneProductMissingAndOtherInsufficientFailsAsProductNotStockedWithBothFailures() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID missingProduct = UUID.randomUUID();
        UUID insufficientProduct = UUID.randomUUID();
        setStock(insufficientProduct, 1);

        String items = "[" + itemJson(missingProduct, 3) + "," + itemJson(insufficientProduct, 5) + "]";
        sagaQueues().sendCommand(reserveStockCommand(orderId, items));

        List<JsonNode> results = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(results).hasSize(1);
        JsonNode event = results.get(0);
        assertThat(event.get("reasonCode").asText()).isEqualTo("PRODUCT_NOT_STOCKED");
        assertThat(event.get("failures")).hasSize(2);
    }

    @Test
    void malformedMessagesAreDiscardedWithWarnLogAndConsumptionStaysAlive(CapturedOutput output) throws Exception {
        sagaQueues().sendCommand("nao e json");

        UUID unknownTypeOrderId = UUID.randomUUID();
        sagaQueues().sendCommand("""
                {"eventId":"%s","eventType":"Foo","occurredAt":"%s","orderId":"%s","reservationId":"%s",\
                "items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), Instant.now(), unknownTypeOrderId, unknownTypeOrderId,
                UUID.randomUUID()));

        UUID validOrderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        setStock(productId, 1);
        sagaQueues().sendCommand(reserveStockCommand(validOrderId, "[" + itemJson(productId, 5) + "]"));

        List<JsonNode> results = sagaQueues().awaitResultsForOrder(validOrderId, 1);
        assertThat(results).hasSize(1);

        assertThat(output.getOut() + output.getErr()).contains("WARN").contains("descartada");
        assertThat(outboxRowCount(unknownTypeOrderId)).isZero();
    }
}

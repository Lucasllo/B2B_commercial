package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.saga.messaging.dto.ReservationLine;
import com.orderflow.inventory.stock.InventoryService;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, da baixa fisica de estoque na expedicao (D-75, D-57):
 * um {@code ShipStock} na {@code inventory-commands-queue} baixa {@code quantity_on_hand} e
 * {@code quantity_reserved} pela quantidade gravada no livro {@code stock_reservations} (nunca a do
 * comando) e marca a linha como expedida, na mesma transacao. {@code ShipStock} nao gera nenhuma
 * resposta nem {@code STOCK_ADJUSTED} (decisao {@code SHIP_STOCK_ADJUSTED_EVENT=none}), entao as
 * provas sao o {@code GET /inventory/{productId}} e o proprio banco.
 */
class ShipStockConsumptionIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryService inventoryService;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    private SagaQueues sagaQueues() {
        return new SagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    private void setStock(UUID productId, int quantityOnHand) throws Exception {
        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityOnHand":%d}
                                """.formatted(quantityOnHand)))
                .andExpect(status().isOk());
    }

    private JsonNode stock(UUID productId) throws Exception {
        String body = mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String reserveStockCommand(UUID orderId, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","items":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderId, itemsJson);
    }

    private String shipStockCommand(UUID eventId, UUID orderId, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ShipStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","items":%s}
                """.formatted(eventId, Instant.now(), orderId, orderId, itemsJson);
    }

    private String itemJson(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    /** Reserva pela fila real e espera o StockReserved — o livro fica com a linha viva. */
    private void reserve(UUID orderId, String itemsJson) {
        sagaQueues().sendCommand(reserveStockCommand(orderId, itemsJson));
        List<JsonNode> reserved = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(reserved.get(0).get("eventType").asText()).isEqualTo("StockReserved");
    }

    private boolean reservationShipped(UUID orderId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT shipped FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Boolean.class, orderId.toString(), productId);
    }

    private boolean reservationReleased(UUID orderId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT released FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Boolean.class, orderId.toString(), productId);
    }

    private Instant reservationShippedAt(UUID orderId, UUID productId) {
        return jdbcTemplate.queryForObject(
                "SELECT shipped_at FROM inventory.stock_reservations WHERE reservation_id = ? AND product_id = ?",
                Instant.class, orderId.toString(), productId);
    }

    private int stockAdjustedRows(UUID productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.outbox_event WHERE aggregate_id = ? AND event_type = 'STOCK_ADJUSTED'",
                Integer.class, productId.toString());
        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------------------------
    // Tracer (Task 1): ShipStock baixa on_hand e reserved e marca a linha como expedida.
    // -----------------------------------------------------------------------------------------

    @Test
    void shipStockDecrementsOnHandAndReservedAndMarksTheBookRowShipped() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        reserve(orderId, "[" + itemJson(productId, 3) + "]");
        assertThat(stock(productId).get("quantityReserved").asInt()).isEqualTo(3);

        sagaQueues().sendCommand(shipStockCommand(UUID.randomUUID(), orderId, "[" + itemJson(productId, 3) + "]"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode stock = stock(productId);
            assertThat(stock.get("quantityOnHand").asInt()).isEqualTo(7);
            assertThat(stock.get("quantityReserved").asInt()).isZero();
            assertThat(stock.get("quantityAvailable").asInt()).isEqualTo(7);
        });
        assertThat(reservationShipped(orderId, productId)).isTrue();
        assertThat(reservationShippedAt(orderId, productId)).isNotNull();
        assertThat(reservationReleased(orderId, productId)).isFalse();
    }

    @Test
    void shipStockForTwoProductsDecrementsBothUsingTheBookQuantityNotTheCommandOne() throws Exception {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productA, 10);
        setStock(productB, 5);
        reserve(orderId, "[" + itemJson(productA, 2) + "," + itemJson(productB, 4) + "]");

        // O comando traz quantidades DIFERENTES das do livro (9 e 1) — a baixa usa as do livro (2 e 4).
        sagaQueues().sendCommand(shipStockCommand(UUID.randomUUID(), orderId,
                "[" + itemJson(productA, 9) + "," + itemJson(productB, 1) + "]"));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(stock(productA).get("quantityOnHand").asInt()).isEqualTo(8);
            assertThat(stock(productA).get("quantityReserved").asInt()).isZero();
            assertThat(stock(productB).get("quantityOnHand").asInt()).isEqualTo(1);
            assertThat(stock(productB).get("quantityReserved").asInt()).isZero();
        });
        assertThat(reservationShipped(orderId, productA)).isTrue();
        assertThat(reservationShipped(orderId, productB)).isTrue();
    }

    @Test
    void shipStockPublishesNoStockAdjustedEvent() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        int afterSetStock = stockAdjustedRows(productId);
        assertThat(afterSetStock).isEqualTo(1);
        reserve(orderId, "[" + itemJson(productId, 3) + "]");

        sagaQueues().sendCommand(shipStockCommand(UUID.randomUUID(), orderId, "[" + itemJson(productId, 3) + "]"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(reservationShipped(orderId, productId)).isTrue());

        assertThat(stockAdjustedRows(productId)).isEqualTo(afterSetStock);
    }

    // -----------------------------------------------------------------------------------------
    // Task 2: idempotencia pelo livro, anomalias como erro tecnico, liberacoes ignoram expedidas.
    // -----------------------------------------------------------------------------------------

    private String releaseStockCommand(UUID orderId, String reason, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ReleaseStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","reason":"%s","items":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderId, reason, itemsJson);
    }

    /** Reserva, expede e espera a baixa aparecer — deixa o produto em onHand-qtd / reserved 0 (do pedido). */
    private void reserveAndShip(UUID orderId, UUID productId, int quantity) {
        reserve(orderId, "[" + itemJson(productId, quantity) + "]");
        sagaQueues().sendCommand(shipStockCommand(UUID.randomUUID(), orderId, "[" + itemJson(productId, quantity) + "]"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(reservationShipped(orderId, productId)).isTrue());
    }

    private void assertStock(UUID productId, int onHand, int reserved) throws Exception {
        JsonNode stock = stock(productId);
        assertThat(stock.get("quantityOnHand").asInt()).as("quantityOnHand").isEqualTo(onHand);
        assertThat(stock.get("quantityReserved").asInt()).as("quantityReserved").isEqualTo(reserved);
    }

    @Test
    void redeliveredShipStockWithSameOrNewEventIdDoesNotDecrementAgain() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        reserve(orderId, "[" + itemJson(productId, 3) + "]");
        UUID sameEventId = UUID.randomUUID();
        String items = "[" + itemJson(productId, 3) + "]";

        sagaQueues().sendCommand(shipStockCommand(sameEventId, orderId, items));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(reservationShipped(orderId, productId)).isTrue());
        sagaQueues().sendCommand(shipStockCommand(sameEventId, orderId, items));
        sagaQueues().sendCommand(shipStockCommand(UUID.randomUUID(), orderId, items));
        Thread.sleep(3000);

        assertStock(productId, 7, 0);
    }

    @Test
    void shipAllForReservationWithoutBookRowThrowsIllegalStateNotExhaustedRetryAndChangesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);

        assertThatThrownBy(() -> inventoryService.shipAll(orderId, orderId.toString(),
                List.of(new ReservationLine(productId, 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(orderId.toString())
                .hasMessageContaining(productId.toString());
        assertStock(productId, 10, 0);
    }

    @Test
    void shipAllForReleasedReservationThrowsIllegalStateAndChangesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        reserve(orderId, "[" + itemJson(productId, 3) + "]");
        sagaQueues().sendCommand(releaseStockCommand(orderId, "RESERVATION_TIMEOUT", "[" + itemJson(productId, 3) + "]"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(reservationReleased(orderId, productId)).isTrue());
        assertStock(productId, 10, 0);

        assertThatThrownBy(() -> inventoryService.shipAll(orderId, orderId.toString(),
                List.of(new ReservationLine(productId, 3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(orderId.toString());
        assertStock(productId, 10, 0);
        assertThat(reservationShipped(orderId, productId)).isFalse();
    }

    @Test
    void releaseStockAfterShipmentIsIgnoredAndDoesNotTouchAnotherOrdersReservation() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID shippedOrder = UUID.randomUUID();
        UUID otherOrder = UUID.randomUUID();
        setStock(productId, 10);
        reserve(otherOrder, "[" + itemJson(productId, 2) + "]");
        reserveAndShip(shippedOrder, productId, 3);
        // Expedido: onHand 10-3=7, reservado so o do outro pedido (2).
        assertStock(productId, 7, 2);

        sagaQueues().sendCommand(releaseStockCommand(shippedOrder, "RESERVATION_TIMEOUT", "[" + itemJson(productId, 3) + "]"));
        Thread.sleep(3000);

        assertStock(productId, 7, 2);
        // Chamada direta: a guarda isShipped devolve normalmente (sem ela, a CHECK do banco faria a
        // escrita falhar e o ReleaseStock iria para a retentativa/DLQ em vez de ser ignorado).
        assertThatCode(() -> inventoryService.releaseAll(shippedOrder, shippedOrder.toString(),
                List.of(new ReservationLine(productId, 3)))).doesNotThrowAnyException();
        assertStock(productId, 7, 2);
        assertThat(reservationReleased(shippedOrder, productId)).isFalse();
        assertThat(reservationShipped(shippedOrder, productId)).isTrue();
        assertThat(reservationReleased(otherOrder, productId)).isFalse();
    }

    @Test
    void restDeleteAfterShipmentReturns200KeepsQuantitiesAndDoesNotTouchAnotherOrdersReservation() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID shippedOrder = UUID.randomUUID();
        UUID otherOrder = UUID.randomUUID();
        setStock(productId, 10);
        reserve(otherOrder, "[" + itemJson(productId, 2) + "]");
        reserveAndShip(shippedOrder, productId, 3);

        mockMvc.perform(delete("/inventory/" + productId + "/reservations/" + shippedOrder)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(7))
                .andExpect(jsonPath("$.quantityReserved").value(2));

        assertStock(productId, 7, 2);
        assertThat(reservationReleased(shippedOrder, productId)).isFalse();
        assertThat(reservationShipped(shippedOrder, productId)).isTrue();
        assertThat(reservationReleased(otherOrder, productId)).isFalse();
    }

    @Test
    void databaseRefusesARowThatIsBothReleasedAndShipped() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        reserveAndShip(orderId, productId, 3);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE inventory.stock_reservations SET released = true, released_at = now() "
                        + "WHERE reservation_id = ? AND product_id = ?", orderId.toString(), productId))
                .hasMessageContaining("chk_stock_reservations_not_released_and_shipped");
    }

    @Test
    void reserveStockRedeliveredAfterShipmentDoesNotChangeStock() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        reserveAndShip(orderId, productId, 3);
        assertStock(productId, 7, 0);

        // Replay idempotente: reemite StockReserved, sem mexer em inventory nem no livro.
        sagaQueues().sendCommand(reserveStockCommand(orderId, "[" + itemJson(productId, 3) + "]"));
        List<JsonNode> replay = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(replay.get(0).get("eventType").asText()).isEqualTo("StockReserved");

        assertStock(productId, 7, 0);
        assertThat(reservationShipped(orderId, productId)).isTrue();
    }
}

package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.NotificationEventsQueue;
import com.orderflow.order.support.OrderSagaQueues;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, do lado PRODUTOR da linha do tempo do pedido (ORD-10,
 * D-78, D-79): cada transição persistida grava o seu evento {@code ORDER_*} no outbox na MESMA
 * transação da mudança de status, e o relay o entrega na {@code notification-events-queue} com o
 * envelope do NOTIFICATION_EVENT_CONTRACT (campos nulos omitidos, {@code eventId} = id da linha do
 * outbox, {@code occurredAt} = instante gravado pela própria transição).
 *
 * <p>Nenhum consumidor lê a fila neste módulo (o notification-service é outro processo): {@link
 * NotificationEventsQueue} lê e apaga as mensagens, guardando só as do {@code orderId} do teste.
 */
class OrderTimelinePublishingIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${orderflow.messaging.notification-events-queue}")
    private String notificationEventsQueue;

    private NotificationEventsQueue notificationQueue() {
        return new NotificationEventsQueue(sqsAsyncClient, objectMapper, notificationEventsQueue);
    }

    // -----------------------------------------------------------------------------------------
    // Suporte
    // -----------------------------------------------------------------------------------------

    private record TestOrder(UUID orderId, UUID companyId, UUID userId, UUID productId, JsonNode body) {
    }

    private TestOrder createOrder(BigDecimal creditLimit, BigDecimal price, String sku, int quantity,
                                   String expectedStatus) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stub().registerCreditLimit(companyId, creditLimit);
        stub().registerProduct(productId, sku, "Produto " + sku, price, "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(companyId, userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":%d}]}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestOrder(UUID.fromString(json.get("id").asText()), companyId, userId, productId, json);
    }

    private JsonNode getOrder(UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/" + orderId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Linhas do outbox do pedido na ordem de criação (o relay não as reordena). */
    private List<Map<String, Object>> outboxRows(UUID orderId) {
        return jdbcTemplate.queryForList(
                "SELECT id, event_type, payload, published_at, attempts, last_error "
                        + "FROM \"order\".outbox_event WHERE aggregate_id = ? ORDER BY created_at",
                orderId.toString());
    }

    private List<String> outboxTypes(UUID orderId) {
        return outboxRows(orderId).stream().map(r -> (String) r.get("event_type")).toList();
    }

    private JsonNode payloadOf(Map<String, Object> row) throws Exception {
        return objectMapper.readTree((String) row.get("payload"));
    }

    private static Instant instant(JsonNode node, String field) {
        return OffsetDateTime.parse(node.get(field).asText()).toInstant();
    }

    /** Espera o relay marcar todas as linhas do pedido como publicadas, sem nenhuma tentativa falha. */
    private void awaitAllPublishedWithoutFailures(UUID orderId) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.get("published_at")).isNotNull();
                assertThat(((Number) row.get("attempts")).intValue()).isZero();
                assertThat(row.get("last_error")).isNull();
            });
        });
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 — criação
    // -----------------------------------------------------------------------------------------

    @Test
    void withinLimitCreationWritesCreatedApprovedThenReserveStockAndRelayDeliversBothTimelineEvents()
            throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-1", 3, "RESERVING");

        List<Map<String, Object>> rows = outboxRows(order.orderId());
        assertThat(rows.stream().map(r -> (String) r.get("event_type")).toList())
                .containsExactly("ORDER_CREATED", "ORDER_APPROVED", "ReserveStock");

        JsonNode createdPayload = payloadOf(rows.get(0));
        assertThat(UUID.fromString(createdPayload.get("eventId").asText())).isEqualTo(rows.get(0).get("id"));
        assertThat(createdPayload.get("eventType").asText()).isEqualTo("ORDER_CREATED");
        Instant createdAt = instant(getOrder(order.orderId()), "createdAt");
        assertThat(Instant.parse(createdPayload.get("occurredAt").asText())).isEqualTo(createdAt);
        assertThat(createdPayload.get("orderId").asText()).isEqualTo(order.orderId().toString());
        assertThat(createdPayload.get("companyId").asText()).isEqualTo(order.companyId().toString());
        assertThat(createdPayload.get("createdBy").asText()).isEqualTo(order.userId().toString());
        assertThat(createdPayload.get("total").decimalValue()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(createdPayload.has("carrier")).isFalse();
        assertThat(createdPayload.has("decidedBy")).isFalse();
        assertThat(createdPayload.has("reason")).isFalse();
        createdPayload.forEach(value -> assertThat(value.isNull()).isFalse());

        JsonNode approvedPayload = payloadOf(rows.get(1));
        assertThat(UUID.fromString(approvedPayload.get("eventId").asText())).isEqualTo(rows.get(1).get("id"));
        assertThat(approvedPayload.get("eventType").asText()).isEqualTo("ORDER_APPROVED");
        assertThat(approvedPayload.get("decidedBy").asText()).isEqualTo("SYSTEM");
        assertThat(approvedPayload.get("reason").asText()).isEqualTo("dentro do limite de crédito");
        Instant decidedAt = instant(getOrder(order.orderId()), "decidedAt");
        assertThat(Instant.parse(approvedPayload.get("occurredAt").asText())).isEqualTo(decidedAt);
        // A criacao e a decisao automatica compartilham o mesmo instante (um unico "now").
        assertThat(decidedAt).isEqualTo(createdAt);
        assertThat(approvedPayload.has("createdBy")).isFalse();
        assertThat(approvedPayload.has("total")).isFalse();
        approvedPayload.forEach(value -> assertThat(value.isNull()).isFalse());

        // As duas mensagens chegam a fila REAL de notificacoes, com os mesmos eventIds das linhas.
        List<JsonNode> delivered = notificationQueue().awaitEventsForOrder(order.orderId(), 2);
        Set<String> deliveredIds = delivered.stream().map(e -> e.get("eventId").asText()).collect(Collectors.toSet());
        assertThat(deliveredIds).contains(rows.get(0).get("id").toString(), rows.get(1).get("id").toString());
        assertThat(delivered.stream().map(e -> e.get("eventType").asText()).toList())
                .containsExactlyInAnyOrder("ORDER_CREATED", "ORDER_APPROVED");

        awaitAllPublishedWithoutFailures(order.orderId());
    }

    @Test
    void overTheLimitCreationWritesCreatedAndPendingApprovalAndNoReserveStockNorApproved() throws Exception {
        TestOrder order = createOrder(new BigDecimal("100.00"), new BigDecimal("150.00"), "SKU-TLN-2", 1,
                "PENDING_APPROVAL");

        List<String> types = outboxTypes(order.orderId());
        assertThat(types).containsExactly("ORDER_CREATED", "ORDER_PENDING_APPROVAL");
        assertThat(types).doesNotContain("ORDER_APPROVED", "ReserveStock");

        List<Map<String, Object>> rows = outboxRows(order.orderId());
        JsonNode pending = payloadOf(rows.get(1));
        assertThat(pending.get("eventType").asText()).isEqualTo("ORDER_PENDING_APPROVAL");
        assertThat(Instant.parse(pending.get("occurredAt").asText()))
                .isEqualTo(instant(getOrder(order.orderId()), "createdAt"));
        assertThat(pending.has("decidedBy")).isFalse();
        assertThat(pending.has("createdBy")).isFalse();

        List<JsonNode> delivered = notificationQueue().awaitEventsForOrder(order.orderId(), 2);
        assertThat(delivered.stream().map(e -> e.get("eventType").asText()).toList())
                .containsExactlyInAnyOrder("ORDER_CREATED", "ORDER_PENDING_APPROVAL");
        awaitAllPublishedWithoutFailures(order.orderId());
    }

    // -----------------------------------------------------------------------------------------
    // Task 2 - demais transicoes (aprovacao manual, rejeicao, confirmacao, cancelamento, envio, entrega)
    // -----------------------------------------------------------------------------------------

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    private OrderSagaQueues sagaQueues() {
        return new OrderSagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    private JsonNode awaitStatus(UUID orderId, String expectedStatus) {
        JsonNode[] holder = new JsonNode[1];
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode order = getOrder(orderId);
            assertThat(order.get("status").asText()).isEqualTo(expectedStatus);
            holder[0] = order;
        });
        return holder[0];
    }

    private void publishStockReserved(TestOrder order, int quantity) {
        sagaQueues().publishResult(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReserved",
                "occurredAt", Instant.now().toString(),
                "orderId", order.orderId().toString(),
                "reservationId", order.orderId().toString(),
                "items", List.of(Map.of("productId", order.productId().toString(), "quantity", quantity))));
    }

    private void publishInsufficientStock(TestOrder order, int requested) {
        sagaQueues().publishResult(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReservationFailed",
                "occurredAt", Instant.now().toString(),
                "orderId", order.orderId().toString(),
                "reservationId", order.orderId().toString(),
                "reasonCode", "INSUFFICIENT_STOCK",
                "failures", List.of(Map.of(
                        "productId", order.productId().toString(),
                        "requested", requested,
                        "available", 0))));
    }

    private TestOrder createConfirmedOrder(String sku) throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), sku, 2, "RESERVING");
        publishStockReserved(order, 2);
        awaitStatus(order.orderId(), "CONFIRMED");
        return order;
    }

    private long rowCount(UUID orderId, String eventType) {
        return outboxTypes(orderId).stream().filter(eventType::equals).count();
    }

    private long timelineRowCount(UUID orderId) {
        return outboxTypes(orderId).stream().filter(t -> t.startsWith("ORDER_")).count();
    }

    private Map<String, Object> singleRow(UUID orderId, String eventType) {
        List<Map<String, Object>> rows = outboxRows(orderId).stream()
                .filter(r -> eventType.equals(r.get("event_type"))).toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void manualApprovalWritesApprovedWithTheSellerAndReasonThenReserveStock() throws Exception {
        TestOrder order = createOrder(new BigDecimal("100.00"), new BigDecimal("150.00"), "SKU-TLN-3", 1,
                "PENDING_APPROVAL");
        UUID sellerId = UUID.randomUUID();

        mockMvc.perform(post("/orders/{orderId}/approve", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(sellerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"cliente antigo"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVING"));

        assertThat(outboxTypes(order.orderId()))
                .containsExactly("ORDER_CREATED", "ORDER_PENDING_APPROVAL", "ORDER_APPROVED", "ReserveStock");
        JsonNode approved = payloadOf(singleRow(order.orderId(), "ORDER_APPROVED"));
        assertThat(approved.get("decidedBy").asText()).isEqualTo(sellerId.toString());
        assertThat(approved.get("reason").asText()).isEqualTo("cliente antigo");
        assertThat(Instant.parse(approved.get("occurredAt").asText()))
                .isEqualTo(instant(getOrder(order.orderId()), "decidedAt"));
    }

    @Test
    void rejectionWritesRejectedWithTheReasonAndNeverReserveStock() throws Exception {
        TestOrder order = createOrder(new BigDecimal("100.00"), new BigDecimal("150.00"), "SKU-TLN-4", 1,
                "PENDING_APPROVAL");
        UUID sellerId = UUID.randomUUID();

        mockMvc.perform(post("/orders/{orderId}/reject", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(sellerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"sem historico de pagamento"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(outboxTypes(order.orderId()))
                .containsExactly("ORDER_CREATED", "ORDER_PENDING_APPROVAL", "ORDER_REJECTED");
        JsonNode rejected = payloadOf(singleRow(order.orderId(), "ORDER_REJECTED"));
        assertThat(rejected.get("decidedBy").asText()).isEqualTo(sellerId.toString());
        assertThat(rejected.get("reason").asText()).isEqualTo("sem historico de pagamento");
        assertThat(Instant.parse(rejected.get("occurredAt").asText()))
                .isEqualTo(instant(getOrder(order.orderId()), "decidedAt"));
        assertThat(rowCount(order.orderId(), "ReserveStock")).isZero();
    }

    @Test
    void stockReservedWritesConfirmedWithCarrierAndTrackingAndADuplicateWritesNothing() throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-5", 2, "RESERVING");

        publishStockReserved(order, 2);
        JsonNode confirmed = awaitStatus(order.orderId(), "CONFIRMED");

        JsonNode event = payloadOf(singleRow(order.orderId(), "ORDER_CONFIRMED"));
        assertThat(event.get("carrier").asText()).isEqualTo(confirmed.get("carrier").asText());
        assertThat(event.get("trackingCode").asText()).isEqualTo(confirmed.get("trackingCode").asText());
        assertThat(Instant.parse(event.get("occurredAt").asText())).isEqualTo(instant(confirmed, "confirmedAt"));
        assertThat(event.has("decidedBy")).isFalse();

        publishStockReserved(order, 2);
        Thread.sleep(3000);

        assertThat(rowCount(order.orderId(), "ORDER_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void reservationFailureWritesCancelledAndALateStockReservedOnlyAddsReleaseStock() throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-6", 2, "RESERVING");

        publishInsufficientStock(order, 2);
        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");

        JsonNode event = payloadOf(singleRow(order.orderId(), "ORDER_CANCELLED"));
        assertThat(event.get("cancellationCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(event.get("cancellationReason").asText()).isEqualTo(cancelled.get("cancellationReason").asText());
        assertThat(Instant.parse(event.get("occurredAt").asText())).isEqualTo(instant(cancelled, "cancelledAt"));
        long timelineRowsBefore = timelineRowCount(order.orderId());

        publishStockReserved(order, 2);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(rowCount(order.orderId(), "ReleaseStock")).isEqualTo(1));

        // Sucesso tardio nao e transicao do pedido: nenhuma linha ORDER_* nova.
        assertThat(timelineRowCount(order.orderId())).isEqualTo(timelineRowsBefore);
        assertThat(rowCount(order.orderId(), "ORDER_CONFIRMED")).isZero();
    }

    @Test
    void reservationTimeoutWritesCancelledWithTheTimeoutCode() throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-7", 1, "RESERVING");

        jdbcTemplate.update("UPDATE \"order\".orders SET reservation_started_at = ? WHERE id = ?",
                OffsetDateTime.now().minus(Duration.ofHours(1)), order.orderId());
        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");

        JsonNode event = payloadOf(singleRow(order.orderId(), "ORDER_CANCELLED"));
        assertThat(event.get("cancellationCode").asText()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(event.get("cancellationReason").asText()).isEqualTo(cancelled.get("cancellationReason").asText());
        assertThat(Instant.parse(event.get("occurredAt").asText())).isEqualTo(instant(cancelled, "cancelledAt"));
    }

    @Test
    void shipAndDeliverWriteShippedAndDeliveredWithTheSellerThatActed() throws Exception {
        TestOrder order = createConfirmedOrder("SKU-TLN-8");
        UUID shipper = UUID.randomUUID();
        UUID deliverer = UUID.randomUUID();

        mockMvc.perform(post("/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(shipper)))
                .andExpect(status().isOk());
        JsonNode shipped = payloadOf(singleRow(order.orderId(), "ORDER_SHIPPED"));
        assertThat(shipped.get("shippedBy").asText()).isEqualTo(shipper.toString());
        assertThat(Instant.parse(shipped.get("occurredAt").asText()))
                .isEqualTo(instant(getOrder(order.orderId()), "shippedAt"));
        assertThat(rowCount(order.orderId(), "ShipStock")).isEqualTo(1);

        mockMvc.perform(post("/orders/{orderId}/deliver", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(deliverer)))
                .andExpect(status().isOk());
        JsonNode delivered = payloadOf(singleRow(order.orderId(), "ORDER_DELIVERED"));
        assertThat(delivered.get("deliveredBy").asText()).isEqualTo(deliverer.toString());
        assertThat(Instant.parse(delivered.get("occurredAt").asText()))
                .isEqualTo(instant(getOrder(order.orderId()), "deliveredAt"));
    }

    @Test
    void fullJourneyWritesExactlyFiveTimelineRowsAllPublishedAndDeliveredToTheQueue() throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-9", 2, "RESERVING");
        publishStockReserved(order, 2);
        awaitStatus(order.orderId(), "CONFIRMED");
        mockMvc.perform(post("/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/orders/{orderId}/deliver", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk());

        List<String> timeline = outboxTypes(order.orderId()).stream().filter(t -> t.startsWith("ORDER_")).toList();
        assertThat(timeline).containsExactly(
                "ORDER_CREATED", "ORDER_APPROVED", "ORDER_CONFIRMED", "ORDER_SHIPPED", "ORDER_DELIVERED");

        List<JsonNode> delivered = notificationQueue().awaitEventsForOrder(order.orderId(), 5);
        assertThat(delivered.stream().map(e -> e.get("eventType").asText()).toList())
                .containsExactlyInAnyOrder(
                        "ORDER_CREATED", "ORDER_APPROVED", "ORDER_CONFIRMED", "ORDER_SHIPPED", "ORDER_DELIVERED");
        awaitAllPublishedWithoutFailures(order.orderId());
    }

    @Test
    void refusedShipWritesNoShippedRow() throws Exception {
        TestOrder order = createOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TLN-10", 1, "RESERVING");

        mockMvc.perform(post("/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isConflict());

        assertThat(rowCount(order.orderId(), "ORDER_SHIPPED")).isZero();
        assertThat(rowCount(order.orderId(), "ShipStock")).isZero();
    }
}

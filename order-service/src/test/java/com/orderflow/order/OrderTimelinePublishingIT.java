package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.NotificationEventsQueue;
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
}

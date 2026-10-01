package com.orderflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.notification.history.NotificationRepository;
import com.orderflow.notification.support.TestJwt;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plano 06-04: eventos {@code ORDER_*} publicados na fila real viram a linha do tempo do pedido
 * (D-80, D-81, D-82). Os corpos seguem o NOTIFICATION_EVENT_CONTRACT que o order-service vai
 * produzir em 06-05.
 */
class OrderTimelineIT extends AbstractIntegrationTest {

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${orderflow.notifications.queue-name}")
    private String queueName;

    private static String orderCreatedBody(UUID eventId, UUID orderId, UUID companyId, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"%s","orderId":"%s","companyId":"%s","createdBy":"buyer-1","total":40.00}
                """.formatted(eventId, occurredAt, orderId, companyId);
    }

    @Test
    void orderCreatedPublishedToQueueAppearsInOrderTimelineWithinFifteenSeconds() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        sqsTemplate.send(to -> to.queue(queueName)
                .payload(orderCreatedBody(eventId, orderId, companyId, "2026-09-30T12:00:00.123456Z")));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/orders/" + orderId)
                                .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].entityId").value(orderId.toString()))
                        .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                        .andExpect(jsonPath("$[0].eventType").value("ORDER_CREATED"))
                        .andExpect(jsonPath("$[0].message").value("Pedido criado — total 40.00"))
                        .andExpect(jsonPath("$[0].payload.companyId").value(companyId.toString())));
    }

    private static String event(String type, UUID eventId, UUID orderId, UUID companyId, String occurredAt,
                                String extras) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","orderId":"%s","companyId":"%s"%s}
                """.formatted(eventId, type, occurredAt, orderId, companyId, extras);
    }

    private void send(String body) {
        sqsTemplate.send(to -> to.queue(queueName).payload(body));
    }

    private List<String> timelineTypes(UUID orderId) throws Exception {
        String json = mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> types = new ArrayList<>();
        for (JsonNode node : objectMapper.readTree(json)) {
            types.add(node.get("eventType").asText());
        }
        return types;
    }

    @Test
    void fullJourneyPublishedShuffledComesBackInLifecycleOrderEvenWithSameInstant() {
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        String sameInstant = "2026-09-30T12:00:00.500000Z";

        send(event("ORDER_DELIVERED", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:10:00Z",
                ",\"deliveredBy\":\"seller-1\""));
        send(event("ORDER_CONFIRMED", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:00:02Z",
                ",\"carrier\":\"Expresso Cerrado\",\"trackingCode\":\"AB123456789BR\""));
        send(event("ORDER_APPROVED", UUID.randomUUID(), orderId, companyId, sameInstant,
                ",\"decidedBy\":\"SYSTEM\""));
        send(event("ORDER_SHIPPED", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:05:00Z",
                ",\"shippedBy\":\"seller-1\""));
        send(event("ORDER_CREATED", UUID.randomUUID(), orderId, companyId, sameInstant,
                ",\"createdBy\":\"buyer-1\",\"total\":40.00"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(timelineTypes(orderId)).containsExactly(
                        "ORDER_CREATED", "ORDER_APPROVED", "ORDER_CONFIRMED", "ORDER_SHIPPED", "ORDER_DELIVERED"));
    }

    @Test
    void sadPathCreatedPendingRejectedKeepsOrderAndRejectionReason() {
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        send(event("ORDER_REJECTED", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:05:00Z",
                ",\"decidedBy\":\"seller-1\",\"reason\":\"sem histórico\""));
        send(event("ORDER_PENDING_APPROVAL", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:00:00Z", ""));
        send(event("ORDER_CREATED", UUID.randomUUID(), orderId, companyId, "2026-09-30T12:00:00Z",
                ",\"createdBy\":\"buyer-1\",\"total\":900.00"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/orders/" + orderId)
                                .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(3))
                        .andExpect(jsonPath("$[0].eventType").value("ORDER_CREATED"))
                        .andExpect(jsonPath("$[1].eventType").value("ORDER_PENDING_APPROVAL"))
                        .andExpect(jsonPath("$[2].eventType").value("ORDER_REJECTED"))
                        .andExpect(jsonPath("$[2].message").value(
                                "Pedido rejeitado pelo vendedor seller-1 — motivo: sem histórico")));
    }

    @Test
    void sameConfirmedEventPublishedTwiceYieldsASingleTimelineEntry() {
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID confirmedId = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        String confirmed = event("ORDER_CONFIRMED", confirmedId, orderId, companyId, "2026-09-30T12:00:02Z",
                ",\"carrier\":\"Expresso Cerrado\",\"trackingCode\":\"AB123456789BR\"");

        send(confirmed);
        send(confirmed);
        // Marcador no MESMO pedido, depois das duas copias: quando ele aparece o listener ja leu as duas.
        send(event("ORDER_SHIPPED", marker, orderId, companyId, "2026-09-30T12:05:00Z",
                ",\"shippedBy\":\"seller-1\""));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(timelineTypes(orderId)).contains("ORDER_SHIPPED"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(timelineTypes(orderId)).containsExactly("ORDER_CONFIRMED", "ORDER_SHIPPED"));
    }

    @Test
    void invalidTrackingCodeIsDiscardedAndListenerStaysAlive() {
        UUID invalidOrderId = UUID.randomUUID();
        UUID validOrderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        send(event("ORDER_CONFIRMED", UUID.randomUUID(), invalidOrderId, companyId, "2026-09-30T12:00:02Z",
                ",\"carrier\":\"Expresso Cerrado\",\"trackingCode\":\"INVALIDO\""));
        send(event("ORDER_CONFIRMED", UUID.randomUUID(), validOrderId, companyId, "2026-09-30T12:00:02Z",
                ",\"carrier\":\"Expresso Cerrado\",\"trackingCode\":\"AB123456789BR\""));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(timelineTypes(validOrderId)).containsExactly("ORDER_CONFIRMED"));
        assertThat(notificationRepository.findByEntityId(invalidOrderId.toString())).isEmpty();
    }

    @Test
    void storedItemCarriesCompanyIdAndDeterministicSortKey() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        sqsTemplate.send(to -> to.queue(queueName)
                .payload(orderCreatedBody(eventId, orderId, companyId, "2026-09-30T12:00:00Z")));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var items = notificationRepository.findByEntityId(orderId.toString());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getCompanyId()).isEqualTo(companyId.toString());
            assertThat(items.get(0).getSortKey()).isEqualTo("ORDER_CREATED#" + eventId);
        });
    }
}

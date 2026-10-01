package com.orderflow.notification;

import com.orderflow.notification.history.NotificationRepository;
import com.orderflow.notification.support.TestJwt;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
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

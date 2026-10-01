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
 * Task 1 (tracer): um evento publicado na fila do LocalStack vira historico no DynamoDB e o
 * vendedor o le de volta. Os testes publicam o corpo JSON de contrato exatamente como o
 * inventory-service vai publicar no plano 03-02.
 */
class NotificationEventFlowIT extends AbstractIntegrationTest {

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Value("${orderflow.notifications.queue-name}")
    private String queueName;

    private String stockAdjustedEventBody(UUID eventId, UUID productId, int previous, int next) {
        return """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":%d,"newQuantityOnHand":%d,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(eventId, productId, previous, next);
    }

    @Test
    void publishedStockAdjustedEventBecomesQueryableHistoryWithinFifteenSeconds() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        sqsTemplate.send(to -> to.queue(queueName).payload(stockAdjustedEventBody(eventId, productId, 5, 12)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String token = TestJwt.sellerAdminToken();
            mockMvc.perform(get("/notifications/" + productId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].entityId").value(productId.toString()))
                    .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                    .andExpect(jsonPath("$[0].eventType").value("STOCK_ADJUSTED"))
                    .andExpect(jsonPath("$[0].message").value(
                            "Estoque do produto " + productId + " ajustado de 5 para 12 unidades"))
                    .andExpect(jsonPath("$[0].payload.previousQuantityOnHand").value(5))
                    .andExpect(jsonPath("$[0].payload.newQuantityOnHand").value(12))
                    .andExpect(jsonPath("$[0].occurredAt").value("2026-09-22T12:00:00Z"))
                    .andExpect(jsonPath("$[0].recordedAt").exists());
        });
    }

    @Test
    void repositoryHoldsExactlyOneItemWithDeterministicKeyAfterPublish() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        sqsTemplate.send(to -> to.queue(queueName).payload(stockAdjustedEventBody(eventId, productId, 3, 9)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var items = notificationRepository.findByEntityId(productId.toString());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getEntityId()).isEqualTo(productId.toString());
            assertThat(items.get(0).getSortKey()).isEqualTo("STOCK_ADJUSTED#" + eventId);
        });
    }

    @Test
    void unknownProductIdReturns200WithEmptyList() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID randomProductId = UUID.randomUUID();

        mockMvc.perform(get("/notifications/" + randomProductId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void requestWithoutAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/notifications/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}

package com.orderflow.notification;

import com.orderflow.notification.support.TestJwt;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 2: reentrega sobrescreve, ajustes distintos acumulam, atributo de tipo estrangeiro e
 * mensagem venenosa — tudo contra LocalStack real. Cada teste usa um {@code productId} novo,
 * porque o contexto, a fila e a tabela sao compartilhados pela suite.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${orderflow.notifications.queue-name}")
    private String queueName;

    private String stockAdjustedEventBody(UUID eventId, UUID productId, int previous, int next, String occurredAt) {
        return """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":%d,"newQuantityOnHand":%d,"occurredAt":"%s"}
                """.formatted(eventId, productId, previous, next, occurredAt);
    }

    @Test
    void redeliveringSameMessageOverwritesInsteadOfDuplicating() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        String body = stockAdjustedEventBody(eventId, productId, 5, 12, "2026-09-22T12:00:00Z");

        sqsTemplate.send(to -> to.queue(queueName).payload(body));

        String firstRecordedAt = awaitSingleElementAndReturnRecordedAt(productId, token);

        sqsTemplate.send(to -> to.queue(queueName).payload(body));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var result = mockMvc.perform(get("/notifications/" + productId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andReturn();
            String recordedAtNow = com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$[0].recordedAt").toString();
            assertThat(Instant.parse(recordedAtNow)).isAfter(Instant.parse(firstRecordedAt));
        });
    }

    private String awaitSingleElementAndReturnRecordedAt(UUID productId, String token) {
        java.util.concurrent.atomic.AtomicReference<String> recordedAt = new java.util.concurrent.atomic.AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var result = mockMvc.perform(get("/notifications/" + productId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andReturn();
            recordedAt.set(com.jayway.jsonpath.JsonPath.read(
                    result.getResponse().getContentAsString(), "$[0].recordedAt").toString());
        });
        return recordedAt.get();
    }

    @Test
    void distinctAdjustmentsOfSameProductAccumulateInChronologicalOrder() throws Exception {
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();

        sqsTemplate.send(to -> to.queue(queueName)
                .payload(stockAdjustedEventBody(UUID.randomUUID(), productId, 10, 15, "2026-09-22T12:05:00Z")));
        sqsTemplate.send(to -> to.queue(queueName)
                .payload(stockAdjustedEventBody(UUID.randomUUID(), productId, 5, 10, "2026-09-22T12:00:00Z")));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/" + productId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(2))
                        .andExpect(jsonPath("$[0].occurredAt").value("2026-09-22T12:00:00Z"))
                        .andExpect(jsonPath("$[1].occurredAt").value("2026-09-22T12:05:00Z")));
    }

    @Test
    void foreignTypeAttributeIsIgnoredAndEventIsRecordedNormally() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        String body = stockAdjustedEventBody(eventId, productId, 1, 2, "2026-09-22T12:00:00Z");

        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        sqsAsyncClient.sendMessage(r -> r.queueUrl(queueUrl)
                        .messageBody(body)
                        .messageAttributes(Map.of(
                                SqsHeaders.SQS_DEFAULT_TYPE_HEADER,
                                MessageAttributeValue.builder()
                                        .dataType("String")
                                        .stringValue("com.orderflow.inventory.stock.dto.StockAdjustedEvent")
                                        .build())))
                .join();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/" + productId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].eventId").value(eventId.toString())));
    }

    @Test
    void poisonMessageIsDiscardedWithWarnLogAndConsumptionStaysAlive(CapturedOutput output) throws Exception {
        sqsTemplate.send(to -> to.queue(queueName).payload("isto nao e json"));

        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var attributes = sqsAsyncClient.getQueueAttributes(r -> r.queueUrl(queueUrl)
                    .attributeNames(
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                            QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).join();
            assertThat(attributes.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).isEqualTo("0");
            assertThat(attributes.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).isEqualTo("0");
        });

        assertThat(output.getOut() + output.getErr()).contains("WARN").contains("descartada");

        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        sqsTemplate.send(to -> to.queue(queueName)
                .payload(stockAdjustedEventBody(eventId, productId, 1, 2, "2026-09-22T12:00:00Z")));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/" + productId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1)));
    }
}

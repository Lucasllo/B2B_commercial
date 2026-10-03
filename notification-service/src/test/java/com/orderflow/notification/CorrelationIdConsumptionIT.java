package com.orderflow.notification;

import com.orderflow.notification.history.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Plano 07-07 (D-94, D-97): o atributo SQS {@code correlationId} chega ao MDC do listener e aparece
 * como {@code [id]} nas linhas de recebimento e de registro, contra LocalStack real. Atributo
 * ausente vira um UUID; atributo fora do formato nunca chega ao log (T-07-23). A fila e a tabela
 * sao compartilhadas pela suite, então cada teste usa um pedido ou produto novo e filtra as linhas
 * pelo identificador da entidade.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdConsumptionIT extends AbstractIntegrationTest {

    private static final Pattern UUID_PREFIX = Pattern.compile(
            "\\[[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\]");

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private NotificationRepository notificationRepository;

    @Value("${orderflow.notifications.queue-name}")
    private String queueName;

    private void send(String body, String correlationId) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        sqsAsyncClient.sendMessage(r -> {
            r.queueUrl(queueUrl).messageBody(body);
            if (correlationId != null) {
                r.messageAttributes(Map.of("correlationId", MessageAttributeValue.builder()
                        .dataType("String").stringValue(correlationId).build()));
            }
        }).join();
    }

    private static String orderCreatedBody(UUID orderId) {
        return """
                {"eventId":"%s","eventType":"ORDER_CREATED","occurredAt":"2026-09-30T12:00:00Z","orderId":"%s","companyId":"%s","createdBy":"buyer-1","total":40.00}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID());
    }

    private static String stockAdjustedBody(UUID productId) {
        return """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":1,"newQuantityOnHand":2,"occurredAt":"2026-09-30T12:00:00Z"}
                """.formatted(UUID.randomUUID(), productId);
    }

    private void awaitRecorded(UUID entityId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationRepository.findByEntityId(entityId.toString())).hasSize(1));
    }

    private static String registeredLine(CapturedOutput output, String type, UUID entityId) {
        return Arrays.stream(output.getAll().split("\\R"))
                .filter(l -> l.contains("Evento registrado eventType=" + type + " entityId=" + entityId))
                .findFirst().orElse("");
    }

    @Test
    void orderCreatedWithCorrelationAttributeLogsReceiptAndRecordingWithThatId(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();

        send(orderCreatedBody(orderId), "it-notif-cid-1");

        awaitRecorded(orderId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registeredLine(output, "ORDER_CREATED", orderId)).contains("[it-notif-cid-1]"));
        assertThat(output.getAll().split("\\R"))
                .anyMatch(l -> l.contains("[it-notif-cid-1]") && l.contains("Mensagem recebida da fila"));
    }

    @Test
    void stockAdjustedWithCorrelationAttributeLogsRecordingWithThatId(CapturedOutput output) {
        UUID productId = UUID.randomUUID();

        send(stockAdjustedBody(productId), "it-notif-cid-2");

        awaitRecorded(productId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registeredLine(output, "STOCK_ADJUSTED", productId)).contains("[it-notif-cid-2]"));
        assertThat(output.getAll().split("\\R"))
                .anyMatch(l -> l.contains("[it-notif-cid-2]") && l.contains("Mensagem recebida da fila"));
    }

    @Test
    void eventWithoutAttributeIsRecordedAndTheLogLineCarriesAGeneratedUuid(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();

        send(orderCreatedBody(orderId), null);

        awaitRecorded(orderId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(UUID_PREFIX.matcher(registeredLine(output, "ORDER_CREATED", orderId)).find()).isTrue());
    }

    @Test
    void invalidAttributeIsRecordedAndNeverReachesTheLog(CapturedOutput output) {
        UUID orderId = UUID.randomUUID();

        send(orderCreatedBody(orderId), "bad value!");

        awaitRecorded(orderId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(UUID_PREFIX.matcher(registeredLine(output, "ORDER_CREATED", orderId)).find()).isTrue());
        assertThat(output.getAll()).doesNotContain("bad value!");
    }
}

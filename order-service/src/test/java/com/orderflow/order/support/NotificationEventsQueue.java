package com.orderflow.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Suporte de teste para {@code OrderTimelinePublishingIT} — lê e apaga os eventos {@code ORDER_*}
 * que o {@code OutboxRelay} entrega na {@code notification-events-queue}, guardando só os do {@code
 * orderId} que interessa. Mesma técnica de {@link OrderSagaQueues}: no order-service ninguém
 * consome esta fila (o consumidor é o notification-service), então ela acumula os eventos de TODOS
 * os ITs da JVM e a leitura precisa varrer e descartar o que não é do pedido — por isso o prazo de
 * espera é de 30 s, maior que o das filas de comando.
 */
public final class NotificationEventsQueue {

    private final SqsAsyncClient sqsAsyncClient;
    private final ObjectMapper objectMapper;
    private final String queueName;

    public NotificationEventsQueue(SqsAsyncClient sqsAsyncClient, ObjectMapper objectMapper, String queueName) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    /**
     * Espera até {@code expectedCount} eventos do pedido aparecerem na fila (até 30 s) e devolve
     * todos os que juntou, em ordem de chegada.
     */
    public List<JsonNode> awaitEventsForOrder(UUID orderId, int expectedCount) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    drainOnce(queueUrl, orderId, matches);
                    assertThat(matches).hasSizeGreaterThanOrEqualTo(expectedCount);
                });
        return matches;
    }

    /**
     * Lê e apaga eventos por um prazo fixo, para que a asserção "nenhum evento" não passe só
     * porque a leitura parou cedo demais.
     */
    public List<JsonNode> drainEventsForOrderDuring(UUID orderId, Duration duration) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            drainOnce(queueUrl, orderId, matches);
        }
        return matches;
    }

    private void drainOnce(String queueUrl, UUID orderId, List<JsonNode> matches) {
        var response = sqsAsyncClient.receiveMessage(r -> r.queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(1)
                .messageAttributeNames("All")).join();
        for (Message message : response.messages()) {
            sqsAsyncClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle())).join();
            JsonNode body;
            try {
                body = objectMapper.readTree(message.body());
            } catch (Exception e) {
                continue;
            }
            if (body.has("orderId") && orderId.toString().equals(body.get("orderId").asText())
                    && body.has("eventType") && body.get("eventType").asText().startsWith("ORDER_")) {
                matches.add(body);
            }
        }
    }
}

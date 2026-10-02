package com.orderflow.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Suporte de teste para {@code ReservationResultListenerIT} — publica resultados de reserva direto
 * na {@code order-events-queue} e lê/apaga comandos de compensação ({@code ReleaseStock}) da {@code
 * inventory-commands-queue}, filtrando por {@code orderId}/{@code eventType}. Mesma técnica de
 * drenagem por lote (até 10 mensagens, apagando cada uma recebida) já usada em {@code SagaQueues}
 * do inventory-service/{@code ReservationCommandPublishingIT} do order-service.
 */
public final class OrderSagaQueues {

    private final SqsAsyncClient sqsAsyncClient;
    private final ObjectMapper objectMapper;
    private final String inventoryCommandsQueue;
    private final String orderEventsQueue;

    public OrderSagaQueues(SqsAsyncClient sqsAsyncClient, ObjectMapper objectMapper,
                            String inventoryCommandsQueue, String orderEventsQueue) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.objectMapper = objectMapper;
        this.inventoryCommandsQueue = inventoryCommandsQueue;
        this.orderEventsQueue = orderEventsQueue;
    }

    /** Envia um resultado de reserva (mapa serializado como JSON) direto na {@code order-events-queue}. */
    public void publishResult(Map<String, Object> body) {
        publishResult(body, null);
    }

    /**
     * Igual a {@link #publishResult(Map)}, e quando {@code correlationId} não é nulo anexa o message
     * attribute {@code correlationId} (DataType String).
     */
    public void publishResult(Map<String, Object> body, String correlationId) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(orderEventsQueue)).join().queueUrl();
        String json = toJson(body);
        if (correlationId == null) {
            sqsAsyncClient.sendMessage(r -> r.queueUrl(queueUrl).messageBody(json)).join();
            return;
        }
        sqsAsyncClient.sendMessage(r -> r.queueUrl(queueUrl)
                .messageBody(json)
                .messageAttributes(Map.of("correlationId", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(correlationId)
                        .build()))).join();
    }

    /** Envia um corpo cru (JSON inválido incluso, de propósito) na {@code order-events-queue}. */
    public void publishRawResult(String rawBody) {
        publishRaw(orderEventsQueue, rawBody);
    }

    private void publishRaw(String queueName, String body) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        sqsAsyncClient.sendMessage(r -> r.queueUrl(queueUrl).messageBody(body)).join();
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize test message body", e);
        }
    }

    /**
     * Espera o comando {@code eventType} daquele pedido na {@code inventory-commands-queue} e devolve
     * o message attribute {@code correlationId} (String), ou nulo quando a mensagem não traz o atributo.
     */
    public String awaitCommandCorrelationId(UUID orderId, String eventType) {
        return awaitQueuedCommand(orderId, eventType).correlationId();
    }

    /**
     * Mesma espera de {@link #awaitCommandCorrelationId}, devolvendo também o corpo para o teste
     * afirmar que o envelope de negócio não ganhou campo novo.
     */
    public QueuedCommand awaitQueuedCommand(UUID orderId, String eventType) {
        List<QueuedCommand> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainQueuedCommandsOnce(queueUrl, orderId, eventType, matches);
                    assertThat(matches).isNotEmpty();
                });
        return matches.get(0);
    }

    public record QueuedCommand(JsonNode body, String correlationId) {
    }

    /**
     * Espera até {@code expectedCount} comandos do {@code eventType} pedido para {@code orderId}
     * aparecerem na {@code inventory-commands-queue}, até 15 segundos, lendo e apagando até 10
     * mensagens por chamada.
     */
    public List<JsonNode> awaitCommandsForOrder(UUID orderId, String eventType, int expectedCount) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainCommandsOnce(queueUrl, orderId, eventType, matches);
                    assertThat(matches).hasSizeGreaterThanOrEqualTo(expectedCount);
                });
        return matches;
    }

    /**
     * Lê e apaga comandos da {@code inventory-commands-queue} por um prazo fixo, para que a
     * asserção "nenhum comando" não passe só porque a leitura parou cedo demais.
     */
    public List<JsonNode> drainCommandsForOrderDuring(UUID orderId, String eventType, Duration duration) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            drainCommandsOnce(queueUrl, orderId, eventType, matches);
        }
        return matches;
    }

    private void drainQueuedCommandsOnce(String queueUrl, UUID orderId, String eventType, List<QueuedCommand> matches) {
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
                    && body.has("eventType") && eventType.equals(body.get("eventType").asText())) {
                MessageAttributeValue attribute = message.messageAttributes().get("correlationId");
                String correlationId = attribute == null ? null : attribute.stringValue();
                matches.add(new QueuedCommand(body, correlationId));
            }
        }
    }

    private void drainCommandsOnce(String queueUrl, UUID orderId, String eventType, List<JsonNode> matches) {
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
                    && body.has("eventType") && eventType.equals(body.get("eventType").asText())) {
                matches.add(body);
            }
        }
    }
}

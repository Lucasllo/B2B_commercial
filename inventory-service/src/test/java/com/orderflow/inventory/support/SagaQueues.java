package com.orderflow.inventory.support;

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
 * Suporte de teste para {@code ReservationCommandConsumptionIT}/{@code IdempotentReservationIT} —
 * envia comandos direto na {@code inventory-commands-queue} e lê/apaga resultados da
 * {@code order-events-queue}, filtrando por {@code orderId}. Mesma técnica de drenagem por lote
 * (até 10 mensagens, apagando cada uma recebida) já usada em
 * {@code StockAdjustedEventPublishingIT}/{@code ReservationCommandPublishingIT} — esta suíte não
 * tem consumidor da fila de resultado, então ela acumula mensagens de todas as classes de teste da
 * JVM.
 */
public final class SagaQueues {

    private final SqsAsyncClient sqsAsyncClient;
    private final ObjectMapper objectMapper;
    private final String inventoryCommandsQueue;
    private final String orderEventsQueue;

    public SagaQueues(SqsAsyncClient sqsAsyncClient, ObjectMapper objectMapper,
                       String inventoryCommandsQueue, String orderEventsQueue) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.objectMapper = objectMapper;
        this.inventoryCommandsQueue = inventoryCommandsQueue;
        this.orderEventsQueue = orderEventsQueue;
    }

    public void sendCommand(String json) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
        sqsAsyncClient.sendMessage(r -> r.queueUrl(queueUrl).messageBody(json)).join();
    }

    /**
     * Espera até {@code expectedCount} resultados para {@code orderId} aparecerem na
     * {@code order-events-queue}, até 15 segundos, lendo e apagando até 10 mensagens por chamada.
     */
    public List<JsonNode> awaitResultsForOrder(UUID orderId, int expectedCount) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(orderEventsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainOnce(queueUrl, orderId, matches);
                    assertThat(matches).hasSizeGreaterThanOrEqualTo(expectedCount);
                });
        return matches;
    }

    /**
     * Lê e apaga mensagens da {@code order-events-queue} por um prazo fixo, para que a asserção
     * "nenhum resultado" não passe só porque a leitura parou cedo demais.
     */
    public List<JsonNode> drainResultsForOrderDuring(UUID orderId, Duration duration) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(orderEventsQueue)).join().queueUrl();
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
            if (body.has("orderId") && orderId.toString().equals(body.get("orderId").asText())) {
                matches.add(body);
            }
        }
    }
}

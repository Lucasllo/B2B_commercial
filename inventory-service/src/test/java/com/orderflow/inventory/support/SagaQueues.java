package com.orderflow.inventory.support;

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
import java.util.Set;
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
 *
 * <p>[Rule 1 - Bug] {@code drainOnce} APAGA toda mensagem lida da fila, ainda que não pertença ao
 * {@code orderId} procurado (mesmo padrão dos ITs analogamente citados acima) — correto quando só
 * um {@code orderId} está em jogo por vez, mas descartaria silenciosamente o resultado de um
 * SEGUNDO pedido concorrente cujo evento chegasse na mesma janela de leitura. {@link
 * #awaitResultsForOrders(Set, int)} existe exatamente para o teste de disputa concorrente
 * (Task 2, D-65): espera por QUALQUER pedido do conjunto na MESMA rodada de dreno, nunca perdendo o
 * resultado do outro pedido enquanto espera pelo primeiro.
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
        sendCommand(json, null);
    }

    /**
     * Igual a {@link #sendCommand(String)}, e quando {@code correlationId} não é nulo anexa o message
     * attribute {@code correlationId} (DataType String).
     */
    public void sendCommand(String json, String correlationId) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
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

    /**
     * Espera o resultado daquele pedido na {@code order-events-queue} e devolve o message attribute
     * {@code correlationId} (String), ou nulo quando a mensagem não traz o atributo.
     */
    public String awaitResultCorrelationId(UUID orderId) {
        List<String> found = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(orderEventsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainResultCorrelationOnce(queueUrl, orderId, found);
                    assertThat(found).isNotEmpty();
                });
        return found.get(0);
    }

    /**
     * Espera até {@code expectedCount} resultados para {@code orderId} aparecerem na
     * {@code order-events-queue}, até 15 segundos, lendo e apagando até 10 mensagens por chamada.
     */
    public List<JsonNode> awaitResultsForOrder(UUID orderId, int expectedCount) {
        return awaitResultsForOrders(Set.of(orderId), expectedCount);
    }

    /**
     * Igual a {@link #awaitResultsForOrder(UUID, int)}, mas casando QUALQUER {@code orderId} do
     * conjunto na mesma rodada de dreno — necessário quando dois pedidos concorrentes podem ter
     * seus resultados na fila ao mesmo tempo (Task 2, disputa pelas últimas unidades): esperar por
     * um de cada vez, em chamadas separadas, apagaria o resultado do outro antes de sua própria
     * chamada ter a chance de vê-lo.
     */
    public List<JsonNode> awaitResultsForOrders(Set<UUID> orderIds, int expectedCount) {
        List<JsonNode> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(orderEventsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainOnce(queueUrl, orderIds, matches);
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
            drainOnce(queueUrl, Set.of(orderId), matches);
        }
        return matches;
    }

    private void drainResultCorrelationOnce(String queueUrl, UUID orderId, List<String> found) {
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
            if (body.has("orderId") && orderId.equals(UUID.fromString(body.get("orderId").asText()))) {
                MessageAttributeValue attribute = message.messageAttributes().get("correlationId");
                found.add(attribute == null ? null : attribute.stringValue());
            }
        }
    }

    private void drainOnce(String queueUrl, Set<UUID> orderIds, List<JsonNode> matches) {
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
            if (body.has("orderId") && orderIds.contains(UUID.fromString(body.get("orderId").asText()))) {
                matches.add(body);
            }
        }
    }
}

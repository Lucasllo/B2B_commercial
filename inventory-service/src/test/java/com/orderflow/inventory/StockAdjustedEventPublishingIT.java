package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.support.TestJwt;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra LocalStack real, de que o ajuste de estoque publica exatamente uma mensagem de
 * contrato na fila {@code notification-events-queue} depois do commit (Task 1). Esta suite nao
 * tem consumidor — a fila acumula mensagens de todas as classes de teste da JVM, entao todo teste
 * le e apaga as mensagens que encontra, guardando so as do {@code productId} que interessa.
 */
class StockAdjustedEventPublishingIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${orderflow.messaging.notification-events-queue}")
    private String queueName;

    private String setStockPayload(int quantityOnHand) {
        return """
                {"quantityOnHand":%d}
                """.formatted(quantityOnHand);
    }

    @Test
    void adjustingStockTwicePublishesOneContractMessagePerAdjustmentWithCorrectPreviousQuantity() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.quantityOnHand").value(7))
                .andExpect(jsonPath("$.quantityReserved").value(0))
                .andExpect(jsonPath("$.quantityAvailable").value(7));

        List<ReceivedMessage> firstBatch = awaitMessagesForProduct(productId, 1);
        assertThat(firstBatch).hasSize(1);
        JsonNode firstEvent = firstBatch.get(0).body();
        assertContractShape(firstEvent, productId, 0, 7);
        assertThat(firstBatch.get(0).attributeKeys()).doesNotContain(SqsHeaders.SQS_DEFAULT_TYPE_HEADER);
        UUID firstEventId = UUID.fromString(firstEvent.get("eventId").asText());

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(12)))
                .andExpect(status().isOk());

        List<ReceivedMessage> secondBatch = awaitMessagesForProduct(productId, 1);
        assertThat(secondBatch).hasSize(1);
        JsonNode secondEvent = secondBatch.get(0).body();
        assertContractShape(secondEvent, productId, 7, 12);
        UUID secondEventId = UUID.fromString(secondEvent.get("eventId").asText());
        assertThat(secondEventId).isNotEqualTo(firstEventId);
    }

    private void assertContractShape(JsonNode event, UUID productId, int expectedPrevious, int expectedNew) {
        assertThat(event.size()).isEqualTo(6);
        assertThat(event.has("eventId")).isTrue();
        assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
        assertThat(event.get("eventType").asText()).isEqualTo("STOCK_ADJUSTED");
        assertThat(event.get("productId").asText()).isEqualTo(productId.toString());
        assertThat(event.get("previousQuantityOnHand").asInt()).isEqualTo(expectedPrevious);
        assertThat(event.get("newQuantityOnHand").asInt()).isEqualTo(expectedNew);
        assertThat(Instant.parse(event.get("occurredAt").asText())).isNotNull();
    }

    /**
     * Le ate 10 mensagens por chamada da fila real, apaga cada mensagem recebida (nesta suite nao
     * ha consumidor — a fila acumula mensagens de todas as classes de teste), e guarda so as que
     * tem o {@code productId} procurado, ate juntar a quantidade esperada ou estourar 15 segundos.
     */
    private List<ReceivedMessage> awaitMessagesForProduct(UUID productId, int expectedCount) {
        List<ReceivedMessage> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainOnce(queueUrl, productId, matches);
                    assertThat(matches).hasSizeGreaterThanOrEqualTo(expectedCount);
                });
        return matches;
    }

    private void drainOnce(String queueUrl, UUID productId, List<ReceivedMessage> matches) {
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
            if (body.has("productId") && productId.toString().equals(body.get("productId").asText())) {
                matches.add(new ReceivedMessage(body, message.messageAttributes().keySet()));
            }
        }
    }

    private record ReceivedMessage(JsonNode body, Set<String> attributeKeys) {
    }
}

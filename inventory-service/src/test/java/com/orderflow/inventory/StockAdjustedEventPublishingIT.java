package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.support.TestJwt;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra LocalStack real, de que o ajuste de estoque publica exatamente uma mensagem de
 * contrato na fila {@code notification-events-queue} depois do commit (Task 1), e de que PUT
 * recusado, reserva e liberacao nao publicam nada (Task 2, D-28). Esta suite nao tem consumidor —
 * a fila acumula mensagens de todas as classes de teste da JVM, entao todo teste le e apaga as
 * mensagens que encontra, guardando so as do {@code productId} que interessa.
 */
class StockAdjustedEventPublishingIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${orderflow.messaging.notification-events-queue}")
    private String queueName;

    private int outboxRowCount(UUID productId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, productId.toString(), eventType);
        return count == null ? 0 : count;
    }

    private boolean outboxRowPublished(UUID eventId) {
        Boolean published = jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM inventory.outbox_event WHERE id = ?",
                Boolean.class, eventId);
        return Boolean.TRUE.equals(published);
    }

    private String setStockPayload(int quantityOnHand) {
        return """
                {"quantityOnHand":%d}
                """.formatted(quantityOnHand);
    }

    private String reservePayload(String reservationId, int quantity) {
        return """
                {"reservationId":"%s","quantity":%d}
                """.formatted(reservationId, quantity);
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 — caminho feliz: ajuste publica exatamente uma mensagem de contrato por vez.
    // -----------------------------------------------------------------------------------------

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
        assertThat(outboxRowCount(productId, "STOCK_ADJUSTED")).isEqualTo(1);
        assertThat(outboxRowPublished(firstEventId)).isTrue();

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
        assertThat(outboxRowCount(productId, "STOCK_ADJUSTED")).isEqualTo(2);
        assertThat(outboxRowPublished(secondEventId)).isTrue();
    }

    // -----------------------------------------------------------------------------------------
    // Task 2 — o que nao pode virar evento: reserva, liberacao, e PUT recusado (400/403/409).
    // -----------------------------------------------------------------------------------------

    @Test
    void rejectedPutReservationAndReleaseDoNotPublishAnyEventBeyondTheOriginalAdjustment() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(5)))
                .andExpect(status().isOk());
        List<ReceivedMessage> afterSetStock = awaitMessagesForProduct(productId, 1);
        assertThat(afterSetStock).hasSize(1);
        assertThat(outboxRowCount(productId, "STOCK_ADJUSTED")).isEqualTo(1);

        mockMvc.perform(post("/inventory/" + productId + "/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservePayload("res-t2-rej", 3)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("stock_below_reserved"));
        assertThat(outboxRowCount(productId, "STOCK_ADJUSTED")).isEqualTo(1);

        mockMvc.perform(delete("/inventory/" + productId + "/reservations/res-t2-rej")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        List<ReceivedMessage> afterActions = drainMessagesForProductDuring(productId, Duration.ofSeconds(3));
        assertThat(afterActions).isEmpty();
        assertThat(outboxRowCount(productId, "STOCK_ADJUSTED")).isEqualTo(1);
    }

    @Test
    void putWithNegativeQuantityReturns400AndDoesNotPublish() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(-1)))
                .andExpect(status().isBadRequest());

        List<ReceivedMessage> messages = drainMessagesForProductDuring(productId, Duration.ofSeconds(3));
        assertThat(messages).isEmpty();
    }

    @Test
    void putWithBuyerTokenReturns403AndDoesNotPublish() throws Exception {
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(10)))
                .andExpect(status().isForbidden());

        List<ReceivedMessage> messages = drainMessagesForProductDuring(productId, Duration.ofSeconds(3));
        assertThat(messages).isEmpty();
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

    /**
     * Le e apaga mensagens da fila real por um prazo fixo, para que a asserção "nenhuma mensagem"
     * nao passe so porque a leitura parou cedo demais.
     */
    private List<ReceivedMessage> drainMessagesForProductDuring(UUID productId, Duration duration) {
        List<ReceivedMessage> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        Instant deadline = Instant.now().plus(duration);
        while (Instant.now().isBefore(deadline)) {
            drainOnce(queueUrl, productId, matches);
        }
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

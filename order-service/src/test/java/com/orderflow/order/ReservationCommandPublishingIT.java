package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.TestJwt;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tracer da Task 1 (ORD-04, D-48 a D-55, D-59, D-61): prova, contra Postgres e LocalStack reais,
 * de que a aprovação automática dentro do limite entra na saga de reserva de estoque pelo padrão
 * Transactional Outbox — status RESERVING, comando ReserveStock gravado na mesma transação e
 * publicado pelo relay na fila real do LocalStack. Escrito ANTES da implementação (RED):
 * inicialmente falha porque o pedido permanece APPROVED e nenhuma linha nasce no outbox.
 *
 * <p>Esta suíte não tem consumidor — a fila acumula mensagens de todas as classes de teste da JVM
 * (mesmo padrão de {@code StockAdjustedEventPublishingIT} do inventory-service), então todo teste
 * lê e apaga as mensagens que encontra, guardando só as do {@code orderId} que interessa.
 */
class ReservationCommandPublishingIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Test
    void withinLimitOrderEntersReservingWithOutboxRowAndCommandReachesTheRealQueue() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-RSV-1", "Produto Reserva 1", new BigDecimal("100.00"), "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":3}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andExpect(jsonPath("$.decidedBy").value("SYSTEM"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(json.get("id").asText());

        // Logo depois da resposta: RESERVING com reservation_started_at preenchido, e exatamente
        // uma linha no outbox para este pedido.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM \"order\".orders WHERE id = ?", String.class, orderId);
        assertThat(status).isEqualTo("RESERVING");
        Instant reservationStartedAt = jdbcTemplate.queryForObject(
                "SELECT reservation_started_at FROM \"order\".orders WHERE id = ?", Instant.class, orderId);
        assertThat(reservationStartedAt).isNotNull();
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                Integer.class, orderId.toString());
        assertThat(outboxRows).isEqualTo(1);
        UUID outboxEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                UUID.class, orderId.toString());

        // Em até 15s chega exatamente uma mensagem com esse orderId na fila real.
        List<ReceivedMessage> matches = awaitMessagesForOrder(orderId, 1);
        assertThat(matches).hasSize(1);
        JsonNode event = matches.get(0).body();
        assertThat(event.get("eventType").asText()).isEqualTo("ReserveStock");
        assertThat(UUID.fromString(event.get("eventId").asText())).isEqualTo(outboxEventId);
        assertThat(event.get("reservationId").asText()).isEqualTo(orderId.toString());
        assertThat(Instant.parse(event.get("occurredAt").asText())).isNotNull();
        assertThat(event.get("items")).hasSize(1);
        assertThat(event.get("items").get(0).get("productId").asText()).isEqualTo(productId.toString());
        assertThat(event.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(matches.get(0).attributeKeys()).doesNotContain(SqsHeaders.SQS_DEFAULT_TYPE_HEADER);

        // A linha do outbox passa a ter published_at preenchido.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Instant publishedAt = jdbcTemplate.queryForObject(
                    "SELECT published_at FROM \"order\".outbox_event WHERE id = ?", Instant.class, outboxEventId);
            assertThat(publishedAt).isNotNull();
        });

        // GET /orders?status=RESERVING do vendedor contém o pedido criado.
        String sellerToken = TestJwt.sellerAdminToken();
        MvcResult listResult = mockMvc.perform(get("/orders?status=RESERVING&size=100")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listResult.getResponse().getContentAsString()).contains(orderId.toString());
    }

    @Test
    void twoItemOrderPublishesItemsInLineOrder() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId1, "SKU-RSV-2A", "Produto Reserva 2A", new BigDecimal("10.00"), "ACTIVE");
        stub().registerProduct(productId2, "SKU-RSV-2B", "Produto Reserva 2B", new BigDecimal("20.00"), "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                                """.formatted(productId1, productId2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(json.get("id").asText());

        List<ReceivedMessage> matches = awaitMessagesForOrder(orderId, 1);
        assertThat(matches).hasSize(1);
        JsonNode items = matches.get(0).body().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("productId").asText()).isEqualTo(productId1.toString());
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("productId").asText()).isEqualTo(productId2.toString());
        assertThat(items.get(1).get("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void orderAboveTheLimitStaysPendingApprovalWithNoOutboxRow() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("100.00"));
        stub().registerProduct(productId, "SKU-RSV-3", "Produto Reserva 3", new BigDecimal("150.00"), "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(json.get("id").asText());

        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ?",
                Integer.class, orderId.toString());
        assertThat(outboxRows).isZero();
    }

    @Test
    void reservingOrderConsumesCreditAndBlocksASecondOrderOverTheLimit() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-RSV-4", "Produto Reserva 4", new BigDecimal("300.00"), "ACTIVE");

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"));

        UUID secondProductId = UUID.randomUUID();
        stub().registerProduct(secondProductId, "SKU-RSV-5", "Produto Reserva 5", new BigDecimal("800.00"), "ACTIVE");
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(secondProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void sagaQueuesExistWithDeadLetterQueueAndMaxReceiveCountThree() {
        assertQueueHasRedrivePolicy(inventoryCommandsQueue, "inventory-commands-dlq");
        assertQueueHasRedrivePolicy("order-events-queue", "order-events-dlq");
    }

    private void assertQueueHasRedrivePolicy(String queueName, String expectedDlqName) {
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(queueName)).join().queueUrl();
        var attributes = sqsAsyncClient.getQueueAttributes(r -> r.queueUrl(queueUrl)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY)).join();
        String redrivePolicy = attributes.attributes()
                .get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY);
        assertThat(redrivePolicy).isNotBlank();
        try {
            JsonNode node = objectMapper.readTree(redrivePolicy);
            assertThat(node.get("deadLetterTargetArn").asText()).endsWith(":" + expectedDlqName);
            assertThat(node.get("maxReceiveCount").asText()).isEqualTo("3");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RedrivePolicy: " + redrivePolicy, e);
        }
    }

    /**
     * Lê até 10 mensagens por chamada da fila real, apaga cada mensagem recebida (nesta suíte não
     * há consumidor — a fila acumula mensagens de todas as classes de teste), e guarda só as que
     * têm o {@code orderId} procurado, até juntar a quantidade esperada ou estourar 15 segundos.
     */
    private List<ReceivedMessage> awaitMessagesForOrder(UUID orderId, int expectedCount) {
        List<ReceivedMessage> matches = new ArrayList<>();
        String queueUrl = sqsAsyncClient.getQueueUrl(r -> r.queueName(inventoryCommandsQueue)).join().queueUrl();
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    drainOnce(queueUrl, orderId, matches);
                    assertThat(matches).hasSizeGreaterThanOrEqualTo(expectedCount);
                });
        return matches;
    }

    private void drainOnce(String queueUrl, UUID orderId, List<ReceivedMessage> matches) {
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
                matches.add(new ReceivedMessage(body, message.messageAttributes().keySet()));
            }
        }
    }

    private record ReceivedMessage(JsonNode body, Set<String> attributeKeys) {
    }
}

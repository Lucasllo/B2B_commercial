package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.OrderSagaQueues;
import com.orderflow.order.support.OrderSagaQueues.QueuedCommand;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova HTTP → MDC → coluna do outbox → message attribute SQS {@code correlationId} (D-94, D-97),
 * contra Postgres e LocalStack reais. O corpo da mensagem continua o envelope de negócio.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdPropagationIT extends AbstractIntegrationTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    @Test
    void postOrdersWithCorrelationIdPublishesReserveStockWithTheSameAttribute(CapturedOutput output) throws Exception {
        UUID orderId = createWithinLimitOrder("it-order-cid-1", "SKU-CID-1");

        String stored = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                String.class, orderId.toString());
        assertThat(stored).isEqualTo("it-order-cid-1");

        QueuedCommand command = sagaQueues().awaitQueuedCommand(orderId, "ReserveStock");
        assertThat(command.correlationId()).isEqualTo("it-order-cid-1");
        assertThat(command.body().get("eventType").asText()).isEqualTo("ReserveStock");
        assertThat(command.body().has("correlationId")).isFalse();

        String logs = output.getOut() + output.getErr();
        assertThat(logs).containsPattern("\\[it-order-cid-1\\].*POST /orders -> 201");
        assertThat(logs).containsPattern("\\[it-order-cid-1\\].*Evento outbox publicado");
    }

    @Test
    void postOrdersWithoutHeaderStoresAGeneratedUuidOnTheOutboxAndTheMessage() throws Exception {
        UUID orderId = createWithinLimitOrder(null, "SKU-CID-2");

        String stored = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                String.class, orderId.toString());
        assertThat(stored).matches(UUID_PATTERN);

        String attribute = sagaQueues().awaitCommandCorrelationId(orderId, "ReserveStock");
        assertThat(attribute).isEqualTo(stored);
    }

    @Test
    void createdOrderStoresTheRequestCorrelationId() throws Exception {
        UUID orderId = createWithinLimitOrder("it-order-cid-2", "SKU-CID-3");

        String stored = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM \"order\".orders WHERE id = ?", String.class, orderId);
        assertThat(stored).isEqualTo("it-order-cid-2");
    }

    @Test
    void stockReservedWithAttributeConfirmsAndKeepsThatIdOnTheOutbox(CapturedOutput output) throws Exception {
        PlacedOrder order = placeWithinLimitOrder(null, "SKU-CID-4");
        sagaQueues().publishResult(stockReservedBody(order.orderId(), order.productId()), "it-result-cid-3");

        awaitOrderStatus(order.orderId(), "CONFIRMED");
        String stored = outboxCorrelationId(order.orderId(), "ORDER_CONFIRMED");
        assertThat(stored).isEqualTo("it-result-cid-3");

        String logs = output.getOut() + output.getErr();
        assertThat(logs).containsPattern("\\[it-result-cid-3\\].*Mensagem recebida");
        assertThat(logs).containsPattern("\\[it-result-cid-3\\].*Pedido confirmado");
    }

    @Test
    void stockReservedWithoutAttributeStillConfirmsWithAGeneratedOutboxId() throws Exception {
        PlacedOrder order = placeWithinLimitOrder(null, "SKU-CID-5");
        sagaQueues().publishResult(stockReservedBody(order.orderId(), order.productId()));

        awaitOrderStatus(order.orderId(), "CONFIRMED");
        String stored = outboxCorrelationId(order.orderId(), "ORDER_CONFIRMED");
        assertThat(stored).matches(UUID_PATTERN);
    }

    @Test
    void aFollowingMessageWithoutAttributeDoesNotReuseThePreviousId() throws Exception {
        PlacedOrder first = placeWithinLimitOrder(null, "SKU-CID-6A");
        PlacedOrder second = placeWithinLimitOrder(null, "SKU-CID-6B");
        sagaQueues().publishResult(stockReservedBody(first.orderId(), first.productId()), "cid-a");
        sagaQueues().publishResult(stockReservedBody(second.orderId(), second.productId()));

        awaitOrderStatus(first.orderId(), "CONFIRMED");
        awaitOrderStatus(second.orderId(), "CONFIRMED");
        assertThat(outboxCorrelationId(first.orderId(), "ORDER_CONFIRMED")).isEqualTo("cid-a");
        assertThat(outboxCorrelationId(second.orderId(), "ORDER_CONFIRMED")).isNotEqualTo("cid-a").isNotBlank();
    }

    @Test
    void postOrdersForwardsTheCorrelationIdToCatalogAndCreditLimit() throws Exception {
        PlacedOrder order = placeWithinLimitOrder("it-sync-cid-5", "SKU-CID-SYNC");

        assertThat(stub().requests())
                .filteredOn(request -> request.path().equals("/products/" + order.productId())
                        || request.path().equals("/companies/" + order.companyId() + "/credit-limit"))
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.correlationIdHeader()).isEqualTo("it-sync-cid-5"));
    }

    private UUID createWithinLimitOrder(String correlationId, String sku) throws Exception {
        return placeWithinLimitOrder(correlationId, sku).orderId();
    }

    private PlacedOrder placeWithinLimitOrder(String correlationId, String sku) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, sku, "Produto " + sku, new BigDecimal("100.00"), "ACTIVE");

        var request = post("/orders")
                .header("Authorization", "Bearer " + buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"items":[{"productId":"%s","quantity":1}]}
                        """.formatted(productId));
        if (correlationId != null) {
            request = request.header("X-Correlation-Id", correlationId);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new PlacedOrder(UUID.fromString(json.get("id").asText()), productId, companyId);
    }

    private void awaitOrderStatus(UUID orderId, String expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM \"order\".orders WHERE id = ?", String.class, orderId);
            assertThat(status).isEqualTo(expected);
        });
    }

    private String outboxCorrelationId(UUID orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = ?",
                String.class, orderId.toString(), eventType);
    }

    private static Map<String, Object> stockReservedBody(UUID orderId, UUID productId) {
        return Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReserved",
                "occurredAt", Instant.now().toString(),
                "orderId", orderId.toString(),
                "reservationId", orderId.toString(),
                "items", List.of(Map.of("productId", productId.toString(), "quantity", 1)));
    }

    private OrderSagaQueues sagaQueues() {
        return new OrderSagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    private record PlacedOrder(UUID orderId, UUID productId, UUID companyId) {
    }
}

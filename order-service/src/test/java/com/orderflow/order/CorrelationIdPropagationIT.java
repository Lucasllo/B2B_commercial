package com.orderflow.order;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
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

    private UUID createWithinLimitOrder(String correlationId, String sku) throws Exception {
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
        return UUID.fromString(json.get("id").asText());
    }

    private OrderSagaQueues sagaQueues() {
        return new OrderSagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }
}

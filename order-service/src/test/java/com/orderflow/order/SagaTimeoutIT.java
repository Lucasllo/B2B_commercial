package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.OrderSagaQueues;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, da garantia de CÓDIGO do "nunca preso" (D-63):
 * {@code SagaTimeoutJob} cancela pedidos presos em {@code RESERVING} além do prazo e grava, na
 * mesma transação, um {@code ReleaseStock} de compensação no outbox.
 *
 * <p>{@code application-test.yml} usa {@code reservation-timeout=10m} ({@code
 * SAGA_TIMEOUT_DEFAULTS}) — um prazo bem acima da duração de qualquer suíte, para que o job (que
 * roda num contexto Spring em CACHE, compartilhado por todas as classes {@code *IT}) nunca cancele
 * um pedido criado por OUTRA classe de teste. Cada teste aqui recua {@code
 * reservation_started_at} do PRÓPRIO pedido via JDBC direto, em vez de esperar o prazo real.
 */
class SagaTimeoutIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    private OrderSagaQueues sagaQueues() {
        return new OrderSagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    // -----------------------------------------------------------------------------------------
    // Suporte
    // -----------------------------------------------------------------------------------------

    private record ReservingOrder(UUID orderId, UUID companyId, UUID productId, String sku) {
    }

    private ReservingOrder createReservingOrder(BigDecimal creditLimit, BigDecimal price, String sku) throws Exception {
        return createReservingOrder(creditLimit, price, sku, null);
    }

    private ReservingOrder createReservingOrder(BigDecimal creditLimit, BigDecimal price, String sku,
                                                 String correlationId) throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, creditLimit);
        stub().registerProduct(productId, sku, "Produto " + sku, price, "ACTIVE");

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
        UUID orderId = UUID.fromString(json.get("id").asText());
        return new ReservingOrder(orderId, companyId, productId, sku);
    }

    private JsonNode getOrder(UUID orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/orders/" + orderId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode awaitStatus(UUID orderId, String expectedStatus) {
        JsonNode[] holder = new JsonNode[1];
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            JsonNode order = getOrder(orderId);
            assertThat(order.get("status").asText()).isEqualTo(expectedStatus);
            holder[0] = order;
        });
        return holder[0];
    }

    /** Recua {@code reservation_started_at} do PRÓPRIO pedido, direto por JDBC — nunca espera o prazo real. */
    private void rewindReservationStartedAt(UUID orderId, Duration ago) {
        jdbcTemplate.update("UPDATE \"order\".orders SET reservation_started_at = ? WHERE id = ?",
                OffsetDateTime.now().minus(ago), orderId);
    }

    private int outboxRowCount(UUID orderId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, orderId.toString(), eventType);
        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------------------------
    // Testes
    // -----------------------------------------------------------------------------------------

    @Test
    void orderStuckPastDeadlineIsCancelledByTimeoutAndReleaseStockReachesInventoryQueue() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("300.00"), "SKU-TO-1");

        rewindReservationStartedAt(order.orderId(), Duration.ofHours(1));

        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        assertThat(cancelled.get("cancellationCode").asText()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(cancelled.get("cancellationReason").asText())
                .isEqualTo("Reserva de estoque não confirmada dentro do prazo — pedido cancelado por tempo esgotado");
        assertThat(cancelled.get("cancelledAt").isNull()).isFalse();
        assertThat(outboxRowCount(order.orderId(), "ReleaseStock")).isEqualTo(1);

        List<JsonNode> commands = sagaQueues().awaitCommandsForOrder(order.orderId(), "ReleaseStock", 1);
        assertThat(commands).hasSize(1);
        JsonNode command = commands.get(0);
        assertThat(command.get("reason").asText()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(command.get("reservationId").asText()).isEqualTo(order.orderId().toString());
        assertThat(command.get("items")).hasSize(1);
        assertThat(command.get("items").get(0).get("productId").asText()).isEqualTo(order.productId().toString());
        assertThat(command.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void orderWithinDeadlineIsUntouchedByTheJobAfterSeveralCycles() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TO-2");

        Thread.sleep(3000);

        JsonNode stillReserving = getOrder(order.orderId());
        assertThat(stillReserving.get("status").asText()).isEqualTo("RESERVING");
    }

    @Test
    void failureResultAfterTimeoutStaysCancelledWithTheSameCancelledAt() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TO-3");
        rewindReservationStartedAt(order.orderId(), Duration.ofHours(1));
        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        String cancelledAt = cancelled.get("cancelledAt").asText();

        sagaQueues().publishResult(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReservationFailed",
                "occurredAt", Instant.now().toString(),
                "orderId", order.orderId().toString(),
                "reservationId", order.orderId().toString(),
                "reasonCode", "INSUFFICIENT_STOCK",
                "failures", List.of()));
        Thread.sleep(3000);

        JsonNode stillCancelled = getOrder(order.orderId());
        assertThat(stillCancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(stillCancelled.get("cancellationCode").asText()).isEqualTo("RESERVATION_TIMEOUT");
        assertThat(stillCancelled.get("cancelledAt").asText()).isEqualTo(cancelledAt);
    }

    @Test
    void lateSuccessAfterTimeoutStaysCancelledAndWritesASecondReleaseStock() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-TO-4");
        rewindReservationStartedAt(order.orderId(), Duration.ofHours(1));
        awaitStatus(order.orderId(), "CANCELLED");

        sagaQueues().publishResult(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReserved",
                "occurredAt", Instant.now().toString(),
                "orderId", order.orderId().toString(),
                "reservationId", order.orderId().toString(),
                "items", List.of(Map.of("productId", order.productId().toString(), "quantity", 1))));

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(outboxRowCount(order.orderId(), "ReleaseStock")).isEqualTo(2));

        JsonNode stillCancelled = getOrder(order.orderId());
        assertThat(stillCancelled.get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void creditReleasedByTimeoutAllowsANewOrderToFitTheLimit() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("600.00"), "SKU-TO-5");
        rewindReservationStartedAt(order.orderId(), Duration.ofHours(1));
        awaitStatus(order.orderId(), "CANCELLED");

        UUID secondProductId = UUID.randomUUID();
        stub().registerProduct(secondProductId, "SKU-TO-5B", "Produto 2", new BigDecimal("600.00"), "ACTIVE");
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(order.companyId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(secondProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"));
    }

    @Test
    void timeoutReusesTheCreationCorrelationIdOnReleaseStockAndOrderCancelled() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"),
                "SKU-TO-CID", "it-timeout-cid-4");
        rewindReservationStartedAt(order.orderId(), Duration.ofHours(1));
        awaitStatus(order.orderId(), "CANCELLED");

        String attribute = sagaQueues().awaitCommandCorrelationId(order.orderId(), "ReleaseStock");
        assertThat(attribute).isEqualTo("it-timeout-cid-4");

        String cancelled = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ORDER_CANCELLED'",
                String.class, order.orderId().toString());
        assertThat(cancelled).isEqualTo("it-timeout-cid-4");
    }
}

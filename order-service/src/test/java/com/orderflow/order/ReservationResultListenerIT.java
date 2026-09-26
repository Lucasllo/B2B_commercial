package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.OrderSagaQueues;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, do consumo do resultado da reserva de estoque na
 * {@code order-events-queue} por {@link com.orderflow.order.saga.messaging.ReservationResultListener}
 * (Task 1: caminhos de FALHA. Task 2: caminho feliz — {@code StockReserved}, duplicatas e sucesso
 * tardio para pedido cancelado). Caminhos de falha vêm antes do caminho feliz no mesmo arquivo
 * (D-68, Success Criteria 3) — o primeiro commit deste arquivo não contém nenhum teste de
 * CONFIRMED (verificado pelo próprio {@code <verify>} da Task 2 no histórico do git).
 *
 * <p>Esta suíte não tem consumidor da {@code inventory-commands-queue} de compensação — {@link
 * OrderSagaQueues} lê e apaga as mensagens que encontra, guardando só as do {@code orderId} que
 * interessa (mesmo padrão de {@code ReservationCommandPublishingIT}/{@code SagaQueues}).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReservationResultListenerIT extends AbstractIntegrationTest {

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
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, creditLimit);
        stub().registerProduct(productId, sku, "Produto " + sku, price, "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
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

    private Map<String, Object> stockReservationFailedBody(UUID orderId, String reasonCode,
                                                            List<Map<String, Object>> failures) {
        return Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReservationFailed",
                "occurredAt", Instant.now().toString(),
                "orderId", orderId.toString(),
                "reservationId", orderId.toString(),
                "reasonCode", reasonCode,
                "failures", failures);
    }

    private Map<String, Object> failureLine(UUID productId, int requested, int available) {
        return Map.of("productId", productId.toString(), "requested", requested, "available", available);
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 — caminhos de FALHA (D-68/Success Criteria 3: escritos antes do caminho feliz).
    // -----------------------------------------------------------------------------------------

    @Test
    void insufficientStockCancelsOrderWithCodeAndReadableReasonAndReleasesCredit() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("600.00"), "SKU-CANC-1");

        sagaQueues().publishResult(stockReservationFailedBody(order.orderId(), "INSUFFICIENT_STOCK",
                List.of(failureLine(order.productId(), 5, 2))));

        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        assertThat(cancelled.get("cancellationCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        String reason = cancelled.get("cancellationReason").asText();
        assertThat(reason).isEqualTo("Estoque insuficiente: produto " + order.sku() + " — disponível 2, solicitado 5");
        assertThat(reason).doesNotContain("Exception").doesNotContain("at com.orderflow").doesNotContain("http://");
        assertThat(cancelled.get("cancelledAt").isNull()).isFalse();
        assertThat(cancelled.get("confirmedAt").isNull()).isTrue();
        assertThat(cancelled.get("decidedBy").asText()).isEqualTo("SYSTEM");
        assertThat(cancelled.get("reason").asText()).isEqualTo("dentro do limite de crédito");

        // Crédito liberado (D-52): um novo pedido de 600.00 da mesma empresa (limite 1000.00)
        // volta a caber e entra em RESERVING.
        UUID secondProductId = UUID.randomUUID();
        stub().registerProduct(secondProductId, "SKU-CANC-1B", "Produto 2", new BigDecimal("600.00"), "ACTIVE");
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
    void productNotStockedCancelsOrderWithItsOwnReasonPrefix() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-CANC-2");

        sagaQueues().publishResult(stockReservationFailedBody(order.orderId(), "PRODUCT_NOT_STOCKED",
                List.of(failureLine(order.productId(), 3, 0))));

        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        assertThat(cancelled.get("cancellationCode").asText()).isEqualTo("PRODUCT_NOT_STOCKED");
        assertThat(cancelled.get("cancellationReason").asText()).startsWith("Produto sem estoque cadastrado:");
    }

    @Test
    void reservationCancelledWithNoFailuresUsesFixedReasonText() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-CANC-3");

        sagaQueues().publishResult(stockReservationFailedBody(order.orderId(), "RESERVATION_CANCELLED", List.of()));

        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        assertThat(cancelled.get("cancellationCode").asText()).isEqualTo("RESERVATION_CANCELLED");
        assertThat(cancelled.get("cancellationReason").asText())
                .isEqualTo("Reserva de estoque cancelada antes de ser processada pelo estoque");
    }

    @Test
    void duplicateFailureEventIsANoOpTheSecondTime() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-CANC-4");
        Map<String, Object> body = stockReservationFailedBody(order.orderId(), "INSUFFICIENT_STOCK",
                List.of(failureLine(order.productId(), 2, 1)));

        sagaQueues().publishResult(body);
        JsonNode firstRead = awaitStatus(order.orderId(), "CANCELLED");
        String firstCancelledAt = firstRead.get("cancelledAt").asText();

        sagaQueues().publishResult(body);
        Thread.sleep(3000);

        JsonNode secondRead = getOrder(order.orderId());
        assertThat(secondRead.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(secondRead.get("cancelledAt").asText()).isEqualTo(firstCancelledAt);
    }

    @Test
    void unknownOrderMalformedBodyAndUnknownEventTypeAreDiscardedAndProcessingContinues(CapturedOutput output)
            throws Exception {
        UUID unknownOrderId = UUID.randomUUID();
        sagaQueues().publishResult(stockReservationFailedBody(unknownOrderId, "INSUFFICIENT_STOCK",
                List.of(failureLine(UUID.randomUUID(), 1, 0))));

        sagaQueues().publishRawResult("nao e json");

        UUID unknownTypeOrderId = UUID.randomUUID();
        sagaQueues().publishRawResult("""
                {"eventId":"%s","eventType":"Foo","occurredAt":"%s","orderId":"%s","reservationId":"%s"}
                """.formatted(UUID.randomUUID(), Instant.now(), unknownTypeOrderId, unknownTypeOrderId));

        ReservingOrder validOrder = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-CANC-5");
        sagaQueues().publishResult(stockReservationFailedBody(validOrder.orderId(), "INSUFFICIENT_STOCK",
                List.of(failureLine(validOrder.productId(), 2, 1))));

        awaitStatus(validOrder.orderId(), "CANCELLED");

        String combined = output.getOut() + output.getErr();
        assertThat(combined).contains("WARN");
        assertThat(combined).contains(unknownOrderId.toString());
        assertThat(combined).contains("descartada");
    }

    @Test
    void cancellationReasonNeverLeaksStackTraceUrlOrExceptionText() throws Exception {
        ReservingOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-CANC-6");

        sagaQueues().publishResult(stockReservationFailedBody(order.orderId(), "PRODUCT_NOT_STOCKED",
                List.of(failureLine(order.productId(), 4, 0))));

        JsonNode cancelled = awaitStatus(order.orderId(), "CANCELLED");
        String reason = cancelled.get("cancellationReason").asText();
        assertThat(reason).doesNotContain("Exception").doesNotContain("at com.orderflow").doesNotContain("http://");
    }
}

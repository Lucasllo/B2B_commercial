package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.order.OrderShipmentService;
import com.orderflow.order.order.exception.InvalidOrderTransitionException;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, contra Postgres e LocalStack reais, da expedição e da entrega pelo vendedor (ORD-10, D-74,
 * D-75, D-76, D-77): {@code POST /orders/{id}/ship} leva CONFIRMED a SHIPPED e grava o comando
 * {@code ShipStock} no outbox na mesma transação (o relay entrega na {@code
 * inventory-commands-queue}); {@code POST /orders/{id}/deliver} leva SHIPPED a DELIVERED sem tocar
 * no estoque. A tabela completa status x ação fica em {@link OrderLifecycleTransitionsIT}.
 */
class OrderShipmentIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private OrderShipmentService orderShipmentService;

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

    private record TestOrder(UUID orderId, UUID companyId, UUID productId) {
    }

    private TestOrder createReservingOrder(BigDecimal creditLimit, BigDecimal price, String sku) throws Exception {
        return createReservingOrder(creditLimit, price, sku, 2);
    }

    private TestOrder createReservingOrder(BigDecimal creditLimit, BigDecimal price, String sku, int quantity)
            throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        stub().registerCreditLimit(companyId, creditLimit);
        stub().registerProduct(productId, sku, "Produto " + sku, price, "ACTIVE");

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":%d}]}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestOrder(UUID.fromString(json.get("id").asText()), companyId, productId);
    }

    private TestOrder createConfirmedOrder(BigDecimal creditLimit, BigDecimal price, String sku) throws Exception {
        return createConfirmedOrder(creditLimit, price, sku, 2);
    }

    private TestOrder createConfirmedOrder(BigDecimal creditLimit, BigDecimal price, String sku, int quantity)
            throws Exception {
        TestOrder order = createReservingOrder(creditLimit, price, sku, quantity);
        sagaQueues().publishResult(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "eventType", "StockReserved",
                "occurredAt", Instant.now().toString(),
                "orderId", order.orderId().toString(),
                "reservationId", order.orderId().toString(),
                "items", List.of(Map.of("productId", order.productId().toString(), "quantity", quantity))));
        awaitStatus(order.orderId(), "CONFIRMED");
        return order;
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

    private JsonNode ship(UUID orderId, UUID sellerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(sellerId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Instant instant(JsonNode order, String field) {
        return java.time.OffsetDateTime.parse(order.get(field).asText()).toInstant();
    }

    private int outboxRowCount(UUID orderId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, orderId.toString(), eventType);
        return count == null ? 0 : count;
    }

    /** Conta so os COMANDOS de estoque do pedido - as linhas ORDER_* de linha do tempo nao entram (D-78). */
    private int outboxRowCount(UUID orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type IN ('ReserveStock', 'ReleaseStock', 'ShipStock')",
                Integer.class, orderId.toString());
        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 — POST /orders/{id}/ship
    // -----------------------------------------------------------------------------------------

    @Test
    void shipMovesAConfirmedOrderToShippedRecordingWhoAndWhenAndKeepingCarrierAndTracking() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-SHP-1");
        JsonNode before = getOrder(order.orderId());
        UUID sellerId = UUID.randomUUID();

        JsonNode shipped = ship(order.orderId(), sellerId);

        assertThat(shipped.get("status").asText()).isEqualTo("SHIPPED");
        assertThat(shipped.get("shippedBy").asText()).isEqualTo(sellerId.toString());
        assertThat(shipped.get("shippedAt").isNull()).isFalse();
        assertThat(shipped.get("deliveredAt").isNull()).isTrue();
        assertThat(shipped.get("deliveredBy").isNull()).isTrue();
        assertThat(shipped.get("carrier").asText()).isEqualTo(before.get("carrier").asText());
        assertThat(shipped.get("trackingCode").asText()).isEqualTo(before.get("trackingCode").asText());
        assertThat(shipped.get("confirmedAt").asText()).isEqualTo(before.get("confirmedAt").asText());

        JsonNode reread = getOrder(order.orderId());
        assertThat(reread.get("status").asText()).isEqualTo("SHIPPED");
        assertThat(reread.get("shippedBy").asText()).isEqualTo(sellerId.toString());
        assertThat(instant(reread, "shippedAt")).isEqualTo(instant(shipped, "shippedAt"));
    }

    @Test
    void shipWritesExactlyOneShipStockToTheOutboxAndTheRelayDeliversItToTheInventoryCommandsQueue() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-SHP-2");
        assertThat(outboxRowCount(order.orderId(), "ShipStock")).isZero();

        ship(order.orderId(), UUID.randomUUID());

        assertThat(outboxRowCount(order.orderId(), "ShipStock")).isEqualTo(1);

        List<JsonNode> messages = sagaQueues().awaitCommandsForOrder(order.orderId(), "ShipStock", 1);
        assertThat(messages).hasSize(1);
        JsonNode message = messages.get(0);
        assertThat(message.get("orderId").asText()).isEqualTo(order.orderId().toString());
        assertThat(message.get("reservationId").asText()).isEqualTo(order.orderId().toString());
        assertThat(message.has("reason")).isFalse();
        assertThat(message.get("items")).hasSize(1);
        assertThat(message.get("items").get(0).get("productId").asText()).isEqualTo(order.productId().toString());
        assertThat(message.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(message.get("eventId").asText()).isNotBlank();
        assertThat(message.get("occurredAt").asText()).isNotBlank();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Integer publishedRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ShipStock' "
                            + "AND published_at IS NOT NULL",
                    Integer.class, order.orderId().toString());
            assertThat(publishedRows).isEqualTo(1);
        });
    }

    @Test
    void shipOnAnOrderThatIsStillReservingIsA409WithFromAndToAndNothingChanges() throws Exception {
        TestOrder order = createReservingOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-SHP-3");

        mockMvc.perform(post("/orders/{orderId}/ship", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_order_transition"))
                .andExpect(jsonPath("$.message").value("Order cannot transition from RESERVING to SHIPPED"));

        assertThat(getOrder(order.orderId()).get("status").asText()).isEqualTo("RESERVING");
        assertThat(outboxRowCount(order.orderId(), "ShipStock")).isZero();
    }

    @Test
    void twoConcurrentShipCallsOnTheSameOrderLetExactlyOneWinAndWriteExactlyOneShipStock() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-SHP-4");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Object> outcomes = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String sellerId = UUID.randomUUID().toString();
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        outcomes.add(orderShipmentService.ship(order.orderId(), sellerId));
                    } catch (InvalidOrderTransitionException e) {
                        outcomes.add(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter(o -> o instanceof InvalidOrderTransitionException).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !(o instanceof InvalidOrderTransitionException)).count()).isEqualTo(1);
        assertThat(outboxRowCount(order.orderId(), "ShipStock")).isEqualTo(1);
        assertThat(getOrder(order.orderId()).get("status").asText()).isEqualTo("SHIPPED");
    }

    @Test
    void aShippedOrderKeepsConsumingCreditSoANewOrderOverTheLimitWaitsForApproval() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("1000.00"), new BigDecimal("600.00"), "SKU-SHP-5", 1);
        ship(order.orderId(), UUID.randomUUID());

        UUID secondProductId = UUID.randomUUID();
        stub().registerProduct(secondProductId, "SKU-SHP-5B", "Produto 2", new BigDecimal("600.00"), "ACTIVE");
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(order.companyId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(secondProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    // -----------------------------------------------------------------------------------------
    // Task 2 — POST /orders/{id}/deliver
    // -----------------------------------------------------------------------------------------

    @Test
    void deliverMovesAShippedOrderToDeliveredWithoutAnyFurtherStockCommandAndStillConsumesCredit() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("2000.00"), new BigDecimal("600.00"), "SKU-DLV-1");
        UUID shipper = UUID.randomUUID();
        UUID deliverer = UUID.randomUUID();
        JsonNode shipped = ship(order.orderId(), shipper);
        sagaQueues().awaitCommandsForOrder(order.orderId(), "ShipStock", 1);
        int outboxRowsAfterShip = outboxRowCount(order.orderId());

        MvcResult result = mockMvc.perform(post("/orders/{orderId}/deliver", order.orderId())
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken(deliverer)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode delivered = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.get("deliveredBy").asText()).isEqualTo(deliverer.toString());
        assertThat(delivered.get("deliveredAt").isNull()).isFalse();
        assertThat(delivered.get("shippedBy").asText()).isEqualTo(shipper.toString());
        assertThat(instant(delivered, "shippedAt")).isEqualTo(instant(shipped, "shippedAt"));
        JsonNode reread = getOrder(order.orderId());
        assertThat(reread.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(instant(reread, "deliveredAt")).isEqualTo(instant(delivered, "deliveredAt"));

        // /deliver nao grava nenhum comando de estoque no outbox (so o ORDER_DELIVERED de linha do tempo) e nenhum comando a mais chega a fila.
        assertThat(outboxRowCount(order.orderId())).isEqualTo(outboxRowsAfterShip);
        assertThat(outboxRowCount(order.orderId(), "ShipStock")).isEqualTo(1);
        assertThat(sagaQueues().drainCommandsForOrderDuring(order.orderId(), "ShipStock", Duration.ofSeconds(3)))
                .isEmpty();

        // DELIVERED continua consumindo credito: 1200.00 ja consumidos + 1500.00 estoura o limite de 2000.00.
        UUID secondProductId = UUID.randomUUID();
        stub().registerProduct(secondProductId, "SKU-DLV-1B", "Produto 2", new BigDecimal("1500.00"), "ACTIVE");
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(order.companyId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(secondProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void deliverTwiceIsA409FromDeliveredToDelivered() throws Exception {
        TestOrder order = createConfirmedOrder(new BigDecimal("1000.00"), new BigDecimal("100.00"), "SKU-DLV-2");
        ship(order.orderId(), UUID.randomUUID());
        String sellerToken = TestJwt.sellerAdminToken();

        mockMvc.perform(post("/orders/{orderId}/deliver", order.orderId())
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/orders/{orderId}/deliver", order.orderId())
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_order_transition"))
                .andExpect(jsonPath("$.message").value("Order cannot transition from DELIVERED to DELIVERED"));
    }
}

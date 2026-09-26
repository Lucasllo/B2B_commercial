package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.saga.messaging.dto.ReservationLine;
import com.orderflow.inventory.stock.InventoryService;
import com.orderflow.inventory.support.SagaQueues;
import com.orderflow.inventory.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova do Success Criteria 4 do ROADMAP (D-65, ORD-06) e dos demais casos de replay/concorrência/
 * livro inconsistente da Task 2 — republicação do comando decrementa a disponibilidade uma única
 * vez, reemitindo um {@code StockReserved} por entrega com {@code eventId} distintos; falha
 * anterior não deixa rastro (é reavaliada); disputa concorrente pelas últimas unidades nunca vende
 * além do estoque; livro parcialmente preenchido é anomalia técnica.
 */
class IdempotentReservationIT extends AbstractIntegrationTest {

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryService inventoryService;

    @Value("${orderflow.messaging.inventory-commands-queue}")
    private String inventoryCommandsQueue;

    @Value("${orderflow.messaging.order-events-queue}")
    private String orderEventsQueue;

    private SagaQueues sagaQueues() {
        return new SagaQueues(sqsAsyncClient, objectMapper, inventoryCommandsQueue, orderEventsQueue);
    }

    private void setStock(UUID productId, int quantityOnHand) throws Exception {
        String token = TestJwt.sellerAdminToken();
        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityOnHand":%d}
                                """.formatted(quantityOnHand)))
                .andExpect(status().isOk());
    }

    private String reserveStockCommand(UUID orderId, String itemsJson) {
        return """
                {"eventId":"%s","eventType":"ReserveStock","occurredAt":"%s",\
                "orderId":"%s","reservationId":"%s","items":%s}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderId, itemsJson);
    }

    private String itemJson(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }

    private int outboxRowCount(UUID orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.outbox_event WHERE aggregate_id = ?",
                Integer.class, orderId.toString());
        return count == null ? 0 : count;
    }

    private int stockReservationCount(String reservationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory.stock_reservations WHERE reservation_id = ?",
                Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    @Test
    void resendingTheSameCommandReservesOnceAndReemitsStockReservedWithDistinctEventIds() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 10);
        String sameBody = reserveStockCommand(orderId, "[" + itemJson(productId, 3) + "]");

        sagaQueues().sendCommand(sameBody);
        List<JsonNode> firstResults = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(firstResults).hasSize(1);
        assertThat(firstResults.get(0).get("eventType").asText()).isEqualTo("StockReserved");
        UUID firstEventId = UUID.fromString(firstResults.get(0).get("eventId").asText());

        // Reentrega do MESMO corpo (mesmo eventId do comando) — a fila ja apagou o primeiro
        // resultado, entao esta segunda espera busca so a mensagem nova.
        sagaQueues().sendCommand(sameBody);
        List<JsonNode> secondResults = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(secondResults).hasSize(1);
        assertThat(secondResults.get(0).get("eventType").asText()).isEqualTo("StockReserved");
        UUID secondEventId = UUID.fromString(secondResults.get(0).get("eventId").asText());
        assertThat(secondEventId).isNotEqualTo(firstEventId);

        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(3));
        assertThat(stockReservationCount(orderId.toString())).isEqualTo(1);

        // O mesmo reservationId (orderId), agora com um eventId de comando NOVO — continua 3.
        sagaQueues().sendCommand(reserveStockCommand(orderId, "[" + itemJson(productId, 3) + "]"));
        List<JsonNode> thirdResults = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(thirdResults).hasSize(1);
        assertThat(thirdResults.get(0).get("eventType").asText()).isEqualTo("StockReserved");
        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(3));
    }

    @Test
    void previousFailureLeavesNoTraceAndIsReevaluatedAfterStockIsAdjusted() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        setStock(productId, 2);
        String sameBody = reserveStockCommand(orderId, "[" + itemJson(productId, 5) + "]");

        sagaQueues().sendCommand(sameBody);
        List<JsonNode> failureResults = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(failureResults).hasSize(1);
        assertThat(failureResults.get(0).get("eventType").asText()).isEqualTo("StockReservationFailed");
        assertThat(stockReservationCount(orderId.toString())).isZero();

        setStock(productId, 10);

        sagaQueues().sendCommand(sameBody);
        List<JsonNode> successResults = sagaQueues().awaitResultsForOrder(orderId, 1);
        assertThat(successResults).hasSize(1);
        assertThat(successResults.get(0).get("eventType").asText()).isEqualTo("StockReserved");

        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(5));
    }

    @Test
    void concurrentOrdersDisputingTheLastUnitsNeverSellBeyondStock() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderIdA = UUID.randomUUID();
        UUID orderIdB = UUID.randomUUID();
        setStock(productId, 5);

        sagaQueues().sendCommand(reserveStockCommand(orderIdA, "[" + itemJson(productId, 3) + "]"));
        sagaQueues().sendCommand(reserveStockCommand(orderIdB, "[" + itemJson(productId, 3) + "]"));

        // Espera pelos DOIS orderId na MESMA rodada de dreno (Set) — esperar um de cada vez
        // apagaria o resultado do outro antes de sua propria chamada te-lo visto (ambos podem
        // chegar na fila dentro da mesma janela de leitura).
        List<JsonNode> results = sagaQueues().awaitResultsForOrders(Set.of(orderIdA, orderIdB), 2);
        assertThat(results).hasSize(2);
        JsonNode resultA = results.stream()
                .filter(r -> orderIdA.toString().equals(r.get("orderId").asText())).findFirst().orElseThrow();
        JsonNode resultB = results.stream()
                .filter(r -> orderIdB.toString().equals(r.get("orderId").asText())).findFirst().orElseThrow();

        List<String> types = List.of(resultA.get("eventType").asText(), resultB.get("eventType").asText());
        assertThat(types).containsExactlyInAnyOrder("StockReserved", "StockReservationFailed");

        JsonNode failed = "StockReservationFailed".equals(resultA.get("eventType").asText()) ? resultA : resultB;
        assertThat(failed.get("reasonCode").asText()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(failed.get("failures").get(0).get("available").asInt()).isEqualTo(2);

        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityReserved").value(3));
    }

    @Test
    void inconsistentBookThrowsIllegalStateExceptionWithoutTouchingTheOutbox() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        setStock(productId1, 10);
        setStock(productId2, 10);

        // Reserva REST direta (SELLER_ADMIN) usando o orderId como reservationId — anomalia so
        // possivel manualmente, nunca pela saga real.
        mockMvc.perform(post("/inventory/" + productId1 + "/reservations")
                        .header("Authorization", "Bearer " + TestJwt.sellerAdminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","quantity":1}
                                """.formatted(orderId)))
                .andExpect(status().isOk());

        List<ReservationLine> lines = List.of(
                new ReservationLine(productId1, 2),
                new ReservationLine(productId2, 3));

        assertThatThrownBy(() -> inventoryService.reserveAll(orderId, orderId.toString(), lines))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outboxRowCount(orderId)).isZero();
    }
}

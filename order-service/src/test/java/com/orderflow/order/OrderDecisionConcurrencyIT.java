package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.ConcurrentRequests;
import com.orderflow.order.support.OrderTestInfrastructure;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova por socket real de que decisões concorrentes não se sobrepõem, e de que a aprovação manual
 * é vista pelas criações que vêm depois (ORD-03, D-46 sobre D-40, T-04-22, T-04-23). <b>Não</b>
 * estende {@link AbstractIntegrationTest}: a base usa {@code MockMvc}, que despacha a requisição
 * dentro do processo sem socket real — não atenderia a força probatória exigida. Modelada em
 * {@code CreditLimitBoundaryConcurrencyIT} (04-01): servidor embutido em porta aleatória, disparo
 * via {@link ConcurrentRequests#fireTogether}, sincronizado por {@code CyclicBarrier}.
 *
 * <p>O terceiro cenário ({@link #manualApprovalOfPendingOrderIsSeenByConcurrentCreationsOfTheSameCompany})
 * é a prova de que D-46 (a aprovação manual passa pela mesma trava da criação) tem efeito: sem a
 * trava, uma criação concorrente poderia somar a exposição da empresa antes de a aprovação manual
 * comitar, e aprovar automaticamente por cima de uma decisão que o vendedor acabou de tomar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
class OrderDecisionConcurrencyIT {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        OrderTestInfrastructure.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void approvalAndRejectionFiredTogetherOnTheSamePendingOrderNeverBothSucceed() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID companyId = UUID.randomUUID();
            UUID productId = UUID.randomUUID();
            String buyerToken = TestJwt.buyerToken(companyId);
            OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyId, new BigDecimal("100.00"));
            OrderTestInfrastructure.STUB_SERVER.registerProduct(productId, "SKU-C" + i, "Produto C" + i,
                    new BigDecimal("150.00"), "ACTIVE");

            UUID orderId = createPendingOrder(buyerToken, productId);
            String sellerToken = TestJwt.sellerAdminToken();

            List<HttpRequest> requests = List.of(
                    buildApproveRequest(orderId, sellerToken, null),
                    buildRejectRequest(orderId, sellerToken, "sem histórico de pagamento"));

            List<ConcurrentRequests.Result> results = ConcurrentRequests.fireTogether(requests);

            assertThat(results).as("no 5xx for pair %d", i).noneMatch(r -> r.statusCode() >= 500);
            long okCount = results.stream().filter(r -> r.statusCode() == 200).count();
            long conflictCount = results.stream().filter(r -> r.statusCode() == 409).count();
            assertThat(okCount).as("exactly one 200 for pair %d", i).isEqualTo(1);
            assertThat(conflictCount).as("exactly one 409 for pair %d", i).isEqualTo(1);

            String winningStatus = results.stream()
                    .filter(r -> r.statusCode() == 200)
                    .map(r -> bodyField(r.body(), "status"))
                    .findFirst()
                    .orElseThrow();
            String dbStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM \"order\".orders WHERE id = ?", String.class, orderId);
            assertThat(dbStatus).as("final DB status for pair %d", i).isEqualTo(winningStatus);

            // D-48: a contagem de linhas ReserveStock no outbox é 1 se a aprovação venceu (o
            // pedido entrou na saga) e 0 se a rejeição venceu (rejeitar nunca inicia a saga).
            int expectedOutboxRows = "RESERVING".equals(winningStatus) ? 1 : 0;
            Integer outboxRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                    Integer.class, orderId.toString());
            assertThat(outboxRows).as("outbox ReserveStock rows for pair %d", i).isEqualTo(expectedOutboxRows);
        }
    }

    @Test
    void tenSimultaneousApprovalsOfTheSamePendingOrderYieldExactlyOneWinner() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyId, new BigDecimal("100.00"));
        OrderTestInfrastructure.STUB_SERVER.registerProduct(productId, "SKU-D1", "Produto D1",
                new BigDecimal("150.00"), "ACTIVE");
        UUID orderId = createPendingOrder(buyerToken, productId);

        List<UUID> sellerIds = new ArrayList<>();
        List<HttpRequest> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID sellerId = UUID.randomUUID();
            sellerIds.add(sellerId);
            requests.add(buildApproveRequest(orderId, TestJwt.sellerAdminToken(sellerId), null));
        }

        List<ConcurrentRequests.Result> results = ConcurrentRequests.fireTogether(requests);
        assertThat(results).noneMatch(r -> r.statusCode() >= 500);

        long okCount = results.stream().filter(r -> r.statusCode() == 200).count();
        long conflictCount = results.stream().filter(r -> r.statusCode() == 409).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(9);

        ConcurrentRequests.Result winner = results.stream()
                .filter(r -> r.statusCode() == 200)
                .findFirst()
                .orElseThrow();
        String winningSellerId = bodyField(winner.body(), "decidedBy");
        boolean winnerIsAKnownContender = sellerIds.stream().anyMatch(id -> id.toString().equals(winningSellerId));
        assertThat(winnerIsAKnownContender).isTrue();

        String dbDecidedBy = jdbcTemplate.queryForObject(
                "SELECT decided_by FROM \"order\".orders WHERE id = ?", String.class, orderId);
        assertThat(dbDecidedBy).isEqualTo(winningSellerId);

        // Dez aprovações simultâneas do mesmo pedido pendente geram exatamente UMA linha
        // ReserveStock no outbox — nunca uma por tentativa (D-48).
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event WHERE aggregate_id = ? AND event_type = 'ReserveStock'",
                Integer.class, orderId.toString());
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    void manualApprovalOfPendingOrderIsSeenByConcurrentCreationsOfTheSameCompany() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID pendingProductId = UUID.randomUUID();
        UUID contenderProductId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        String sellerToken = TestJwt.sellerAdminToken();

        OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyId, new BigDecimal("100.00"));
        OrderTestInfrastructure.STUB_SERVER.registerProduct(pendingProductId, "SKU-E1", "Produto E1",
                new BigDecimal("150.00"), "ACTIVE");
        OrderTestInfrastructure.STUB_SERVER.registerProduct(contenderProductId, "SKU-E2", "Produto E2",
                new BigDecimal("30.00"), "ACTIVE");

        UUID pendingOrderId = createPendingOrder(buyerToken, pendingProductId);

        List<HttpRequest> requests = new ArrayList<>();
        requests.add(buildApproveRequest(pendingOrderId, sellerToken, null));
        for (int i = 0; i < 5; i++) {
            requests.add(buildCreateOrderRequest(buyerToken, contenderProductId, 1));
        }

        List<ConcurrentRequests.Result> results = ConcurrentRequests.fireTogether(requests);
        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 201));

        OffsetDateTime pendingDecidedAt = jdbcTemplate.queryForObject(
                "SELECT decided_at FROM \"order\".orders WHERE id = ?", OffsetDateTime.class, pendingOrderId);

        BigDecimal autoApprovedSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total), 0) FROM \"order\".orders WHERE company_id = ? AND decided_by = 'SYSTEM'",
                BigDecimal.class, companyId);
        assertThat(autoApprovedSum).isLessThanOrEqualTo(new BigDecimal("90.00"));

        List<OffsetDateTime> autoApprovedDecidedAts = jdbcTemplate.queryForList(
                "SELECT decided_at FROM \"order\".orders WHERE company_id = ? AND decided_by = 'SYSTEM'",
                OffsetDateTime.class, companyId);
        assertThat(autoApprovedDecidedAts).allSatisfy(decidedAt ->
                assertThat(decidedAt).isBeforeOrEqualTo(pendingDecidedAt));
    }

    private UUID createPendingOrder(String buyerToken, UUID productId) throws Exception {
        ConcurrentRequests.Result result = ConcurrentRequests.fireTogether(
                List.of(buildCreateOrderRequest(buyerToken, productId, 1))).get(0);
        assertThat(result.statusCode()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.body());
        assertThat(json.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        return UUID.fromString(json.get("id").asText());
    }

    private HttpRequest buildApproveRequest(UUID orderId, String bearerToken, String reason) {
        String body = reason == null ? "{}" : "{\"reason\":\"%s\"}".formatted(reason);
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/orders/" + orderId + "/approve"))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildRejectRequest(UUID orderId, String bearerToken, String reason) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/orders/" + orderId + "/reject"))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"%s\"}".formatted(reason)))
                .build();
    }

    private HttpRequest buildCreateOrderRequest(String bearerToken, UUID productId, int quantity) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/orders"))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}".formatted(productId, quantity)))
                .build();
    }

    private String bodyField(String body, String field) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode value = node.get(field);
            return (value == null || value.isNull()) ? null : value.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response body: " + body, e);
        }
    }
}

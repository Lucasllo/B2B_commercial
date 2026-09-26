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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova por socket real do Success Criteria 5 do ROADMAP (D-40, D-37). <b>Não</b> estende {@link
 * AbstractIntegrationTest}: a base usa {@code MockMvc}, que despacha a requisição dentro do
 * processo sem um socket real — não atenderia a força probatória exigida pelo critério.
 * Modelada em {@code StockReservationConcurrencyIT} (inventory-service, 02-03): servidor embutido
 * em porta aleatória, disparo via {@code java.net.http.HttpClient} da própria JDK sobre um
 * executor de uma thread virtual por tarefa, sincronizado por uma {@code CyclicBarrier}
 * ({@link com.orderflow.order.support.ConcurrentRequests#fireTogether}) dimensionada para o
 * número exato de contendores.
 *
 * <p>O stub HTTP ({@link com.orderflow.order.support.DownstreamStubServer}) responde em paralelo
 * (executor de threads virtuais, ver seu javadoc) — se ele serializasse as respostas, as
 * requisições chegariam à transação escalonadas e a disputa real desapareceria.
 *
 * <p>Cada cenário usa empresa e produto novos: a linha de trava ainda não existe, então a corrida
 * do {@code INSERT} idempotente de {@code ensureExists} também é exercitada sob concorrência.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
class CreditLimitBoundaryConcurrencyIT {

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
    void limite100ComDezContendoresDeSessenta_exatamenteUmAprovado() throws Exception {
        assertBoundary(new BigDecimal("100.00"), new BigDecimal("60.00"), 10, 1);
    }

    @Test
    void limite180ComDezContendoresDeSessenta_exatamenteTresAprovados() throws Exception {
        assertBoundary(new BigDecimal("180.00"), new BigDecimal("60.00"), 10, 3);
    }

    @Test
    void duasEmpresasNovasAlternando_travaDeUmaNaoDecidePelaOutra() throws Exception {
        UUID companyX = UUID.randomUUID();
        UUID companyY = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        BigDecimal unitPrice = new BigDecimal("60.00");
        BigDecimal creditLimit = new BigDecimal("100.00");

        OrderTestInfrastructure.STUB_SERVER.registerProduct(productId, "SKU-X", "Produto X", unitPrice, "ACTIVE");
        OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyX, creditLimit);
        OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyY, creditLimit);

        List<HttpRequest> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID company = (i % 2 == 0) ? companyX : companyY;
            requests.add(buildOrderRequest(TestJwt.buyerToken(company), productId, 1));
        }

        List<ConcurrentRequests.Result> results = ConcurrentRequests.fireTogether(requests);
        assertNoServerErrors(results);

        assertThat(countApproved(companyX)).isEqualTo(1);
        assertThat(countApproved(companyY)).isEqualTo(1);
    }

    private void assertBoundary(BigDecimal creditLimit, BigDecimal unitPrice, int contenders, int expectedApproved)
            throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderTestInfrastructure.STUB_SERVER.registerProduct(productId, "SKU-B", "Produto B", unitPrice, "ACTIVE");
        OrderTestInfrastructure.STUB_SERVER.registerCreditLimit(companyId, creditLimit);
        String token = TestJwt.buyerToken(companyId);

        List<HttpRequest> requests = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            requests.add(buildOrderRequest(token, productId, 1));
        }

        List<ConcurrentRequests.Result> results = ConcurrentRequests.fireTogether(requests);
        assertNoServerErrors(results);

        // Fase 5 (D-50): a decisão automática dentro do limite já entra na saga na mesma
        // transação — o status persistido/observável de um pedido novo aprovado é RESERVING.
        long approvedCount = results.stream().filter(r -> "RESERVING".equals(bodyStatus(r.body()))).count();
        long pendingCount = results.stream().filter(r -> "PENDING_APPROVAL".equals(bodyStatus(r.body()))).count();
        assertThat(approvedCount).as("exact RESERVING count for limit %s", creditLimit).isEqualTo(expectedApproved);
        assertThat(pendingCount).isEqualTo(contenders - expectedApproved);

        BigDecimal expectedSum = unitPrice.multiply(BigDecimal.valueOf(expectedApproved));
        BigDecimal actualSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total), 0) FROM \"order\".orders WHERE company_id = ? AND status = 'RESERVING'",
                BigDecimal.class, companyId);
        assertThat(actualSum).isEqualByComparingTo(expectedSum);

        Integer lockRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".company_credit_lock WHERE company_id = ?",
                Integer.class, companyId);
        assertThat(lockRows).isEqualTo(1);

        // Asserção nova (T-05-01): o número de linhas ReserveStock no outbox da empresa é igual ao
        // número de pedidos RESERVING — nunca pedido avançado sem comando, nem comando sem pedido.
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".outbox_event e "
                        + "JOIN \"order\".orders o ON e.aggregate_id = o.id::text "
                        + "WHERE o.company_id = ? AND e.event_type = 'ReserveStock'",
                Integer.class, companyId);
        assertThat(outboxRows).isEqualTo(expectedApproved);
    }

    private long countApproved(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".orders WHERE company_id = ? AND status = 'RESERVING'",
                Long.class, companyId);
    }

    private void assertNoServerErrors(List<ConcurrentRequests.Result> results) {
        // Toda requisição deve terminar em 201 — nenhuma 5xx e nenhuma disputa esgotada, porque a
        // trava serializa em vez de disputar (nada a reexecutar nesta fase).
        assertThat(results).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
    }

    private String bodyStatus(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.get("status").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response body: " + body, e);
        }
    }

    private HttpRequest buildOrderRequest(String bearerToken, UUID productId, int quantity) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/orders"))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}".formatted(productId, quantity)))
                .build();
    }
}

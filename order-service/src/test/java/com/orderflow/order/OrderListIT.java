package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.nimbusds.jwt.JWTClaimsSet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /orders} (ORD-08/ORD-09, D-47). Task 1 (tracer): escopo por empresa, ordenação e o
 * envelope {@code Page<T>}. Task 2 acrescenta o filtro por status como fila de aprovação, a prova
 * de que a ordenação do servidor prevalece sobre o {@code sort} do cliente, e os limites de
 * paginação.
 */
class OrderListIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private static final Set<String> SUMMARY_KEYS = Set.of(
            "id", "companyId", "status", "total", "createdBy", "createdAt", "decidedBy", "decidedAt");

    private UUID createOrder(String buyerToken, UUID productId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":%d}]}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    // -----------------------------------------------------------------------------------------
    // Task 1 (tracer): escopo por empresa, ordenação e o envelope Page<T>.
    // -----------------------------------------------------------------------------------------

    @Test
    void buyerListsOnlyOwnCompanyOrdersAndSellerAdminListsAllInDescendingCreatedAtOrder() throws Exception {
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerTokenA = TestJwt.buyerToken(companyA);
        String buyerTokenB = TestJwt.buyerToken(companyB);
        String sellerToken = TestJwt.sellerAdminToken();

        stub().registerCreditLimit(companyA, new BigDecimal("100000.00"));
        stub().registerCreditLimit(companyB, new BigDecimal("100000.00"));
        stub().registerProduct(productId, "SKU-LIST-1", "Produto Listagem 1", new BigDecimal("10.00"), "ACTIVE");

        UUID orderA1 = createOrder(buyerTokenA, productId, 1);
        UUID orderA2 = createOrder(buyerTokenA, productId, 1);
        UUID orderA3 = createOrder(buyerTokenA, productId, 1);
        UUID orderB1 = createOrder(buyerTokenB, productId, 1);
        UUID orderB2 = createOrder(buyerTokenB, productId, 1);

        MvcResult buyerAResult = mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();
        JsonNode buyerAJson = objectMapper.readTree(buyerAResult.getResponse().getContentAsString());
        for (JsonNode element : buyerAJson.get("content")) {
            assertThat(element.get("companyId").asText()).isEqualTo(companyA.toString());
        }
        assertNonIncreasingByCreatedAt(buyerAJson);

        MvcResult buyerBResult = mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + buyerTokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        JsonNode buyerBJson = objectMapper.readTree(buyerBResult.getResponse().getContentAsString());
        for (JsonNode element : buyerBJson.get("content")) {
            assertThat(element.get("companyId").asText()).isEqualTo(companyB.toString());
        }

        MvcResult sellerResult = mockMvc.perform(get("/orders?size=100")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andReturn();
        String sellerBody = sellerResult.getResponse().getContentAsString();
        for (UUID expectedId : Set.of(orderA1, orderA2, orderA3, orderB1, orderB2)) {
            assertThat(sellerBody).contains(expectedId.toString());
        }

        // BUYER de empresa sem pedidos.
        String buyerTokenEmpty = TestJwt.buyerToken(UUID.randomUUID());
        mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + buyerTokenEmpty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));

        // Elementos do resumo: exatamente as chaves esperadas, sem "items".
        JsonNode firstElement = buyerAJson.get("content").get(0);
        SUMMARY_KEYS.forEach(key -> assertThat(firstElement.has(key)).as("campo %s presente", key).isTrue());
        Iterator<String> fieldNames = firstElement.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            assertThat(SUMMARY_KEYS).as("campo inesperado no resumo: %s", fieldName).contains(fieldName);
        }
        assertThat(firstElement.has("items")).isFalse();
    }

    private void assertNonIncreasingByCreatedAt(JsonNode pageJson) {
        JsonNode content = pageJson.get("content");
        OffsetDateTime previous = null;
        for (JsonNode element : content) {
            OffsetDateTime current = OffsetDateTime.parse(element.get("createdAt").asText());
            if (previous != null) {
                assertThat(current).isBeforeOrEqualTo(previous);
            }
            previous = current;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Task 2: filtro por status como fila de aprovação, ordenação imposta pelo servidor e limites
    // de paginação — created_at controlado por INSERT direto (JdbcTemplate) para determinismo
    // mesmo com o banco de teste compartilhado por outras classes.
    // -----------------------------------------------------------------------------------------

    private void insertOrder(UUID id, UUID companyId, String status, BigDecimal total, String createdBy,
                              OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO "order".orders (id, company_id, status, total, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                id, companyId, status, total, createdBy, createdAt);
    }

    @Test
    void statusFilterActsAsApprovalQueueAndOrderingAndPaginationAreServerControlled() throws Exception {
        UUID companyA = UUID.randomUUID();
        String buyerTokenA = TestJwt.buyerToken(companyA);
        String sellerToken = TestJwt.sellerAdminToken();
        String createdBy = UUID.randomUUID().toString();

        // created_at no futuro (agora + 1 dia + i minutos) — sempre no topo da ordenação global,
        // mesmo com outras classes de teste inserindo pedidos no banco compartilhado.
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).plusDays(1);

        // Do mais antigo (1) para o mais recente (5) — a listagem devolve do 5º ao 1º.
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();
        UUID order3 = UUID.randomUUID();
        UUID order4 = UUID.randomUUID();
        UUID order5 = UUID.randomUUID();
        insertOrder(order1, companyA, "APPROVED", new BigDecimal("500.00"), createdBy, base.plusMinutes(1));
        insertOrder(order2, companyA, "PENDING_APPROVAL", new BigDecimal("100.00"), createdBy, base.plusMinutes(2));
        insertOrder(order3, companyA, "APPROVED", new BigDecimal("300.00"), createdBy, base.plusMinutes(3));
        insertOrder(order4, companyA, "PENDING_APPROVAL", new BigDecimal("200.00"), createdBy, base.plusMinutes(4));
        insertOrder(order5, companyA, "APPROVED", new BigDecimal("400.00"), createdBy, base.plusMinutes(5));

        // ?status=PENDING_APPROVAL — a fila de aprovação do vendedor, e o mesmo filtro para o BUYER.
        mockMvc.perform(get("/orders?status=PENDING_APPROVAL")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo("PENDING_APPROVAL"))));

        MvcResult sellerPendingResult = mockMvc.perform(get("/orders?status=PENDING_APPROVAL&size=100")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo("PENDING_APPROVAL"))))
                .andReturn();
        String sellerPendingBody = sellerPendingResult.getResponse().getContentAsString();
        assertThat(sellerPendingBody).contains(order2.toString());
        assertThat(sellerPendingBody).contains(order4.toString());

        // Ordem exata do created_at mais recente para o mais antigo: 5,4,3,2,1.
        MvcResult orderedResult = mockMvc.perform(get("/orders?size=100")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        assertExactOrder(orderedResult, order5, order4, order3, order2, order1);

        // sort=total,asc do cliente é descartado — mesma ordem de created_at desc.
        MvcResult sortIgnoredResult = mockMvc.perform(get("/orders?size=100&sort=total,asc")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        assertExactOrder(sortIgnoredResult, order5, order4, order3, order2, order1);

        // Paginação: size=2&page=1 devolve o 3º e o 4º mais recentes (order3, order2).
        mockMvc.perform(get("/orders?size=2&page=1")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(order3.toString()))
                .andExpect(jsonPath("$.content[1].id").value(order2.toString()))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(1));

        // Página além do fim: content vazio, totalElements real.
        mockMvc.perform(get("/orders?size=2&page=9")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(5));

        // Tamanho de página padrão 20; size=500 reduzido a 100.
        mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
        mockMvc.perform(get("/orders?size=500")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        // status inválido (não é um dos 8 estados, inclusive em minúsculas) → 400 invalid_parameter.
        mockMvc.perform(get("/orders?status=FOO")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));
        mockMvc.perform(get("/orders?status=pending_approval")
                        .header("Authorization", "Bearer " + buyerTokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));

        // BUYER sem claim company_id → 403; sem token → 401.
        String buyerWithoutCompany = TestJwt.tokenSignedBy(TestJwt.RSA_KEY,
                new JWTClaimsSet.Builder()
                        .issuer("orderflow-auth-service")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "BUYER")
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                        .build());
        mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + buyerWithoutCompany))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized());
    }

    private void assertExactOrder(MvcResult result, UUID... expectedOrder) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode content = json.get("content");
        assertThat(content).hasSize(expectedOrder.length);
        for (int i = 0; i < expectedOrder.length; i++) {
            assertThat(content.get(i).get("id").asText()).isEqualTo(expectedOrder[i].toString());
        }
    }
}

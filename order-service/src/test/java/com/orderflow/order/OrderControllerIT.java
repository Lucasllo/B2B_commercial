package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tracer da Task 1 (ORD-01/ORD-02/ORD-08/ORD-09): escrito antes da implementação, define o
 * contrato da fatia — criação validada no catálogo, decisão de crédito sob trava, snapshot
 * gravado, e o isolamento por empresa no detalhe.
 */
class OrderControllerIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void buyerCreatesOrderValidatedAgainstCatalogAndDecidedByCreditLimitBoundary() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId, buyerId);

        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-P1", "Parafuso", new BigDecimal("100.00"), "ACTIVE");

        // Pedido 1: 3 unidades a 100.00 = 300.00 — dentro do limite (1000.00) → APPROVED.
        MvcResult firstResult = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":3}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.createdBy").value(buyerId.toString()))
                .andExpect(jsonPath("$.decidedBy").value("SYSTEM"))
                .andExpect(jsonPath("$.reason").value("dentro do limite de crédito"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andExpect(jsonPath("$.items[0].lineNumber").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-P1"))
                .andExpect(jsonPath("$.items[0].name").value("Parafuso"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].subtotal").value(300.00))
                .andReturn();

        String firstBody = firstResult.getResponse().getContentAsString();
        // Asserção sobre o texto do corpo — prova da escala 2 (300.00, nunca 300 ou 300.0).
        assertThat(firstBody).contains("\"total\":300.00");
        JsonNode firstJson = objectMapper.readTree(firstBody);
        UUID firstOrderId = UUID.fromString(firstJson.get("id").asText());
        assertThat(firstResult.getResponse().getHeader("Location")).isEqualTo("/orders/" + firstOrderId);

        // O header Authorization repassado é exatamente o token do BUYER usado na requisição, para
        // os dois vizinhos (catalog-service e auth-service).
        assertThat(stub().requests()).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/products/" + productId);
            assertThat(r.authorizationHeader()).isEqualTo("Bearer " + buyerToken);
        });
        assertThat(stub().requests()).anySatisfy(r -> {
            assertThat(r.path()).isEqualTo("/companies/" + companyId + "/credit-limit");
            assertThat(r.authorizationHeader()).isEqualTo("Bearer " + buyerToken);
        });

        // Depois do primeiro pedido: exatamente 1 linha em company_credit_lock para a empresa, e o
        // snapshot do item gravado com o unit_price correto.
        Integer lockRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".company_credit_lock WHERE company_id = ?",
                Integer.class, companyId);
        assertThat(lockRows).isEqualTo(1);
        BigDecimal snapshotUnitPrice = jdbcTemplate.queryForObject(
                "SELECT unit_price FROM \"order\".order_items WHERE order_id = ?",
                BigDecimal.class, firstOrderId);
        assertThat(snapshotUnitPrice).isEqualByComparingTo("100.00");

        // Pedido 2: 8 unidades a 100.00 = 800.00 — 300.00 + 800.00 > 1000.00 → PENDING_APPROVAL,
        // decidedBy/decidedAt/reason nulos.
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":8}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist());

        // Pedido 3: 7 unidades a 100.00 = 700.00 — o pendente não consome, então
        // 300.00 + 700.00 = 1000.00 → APPROVED (igualdade aprova, D-36).
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":7}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Um companyId estranho no corpo é ignorado — o pedido nasce na empresa do JWT, e o stub
        // registra a chamada de limite no caminho da empresa do JWT, nunca no da outra.
        UUID otherCompanyId = UUID.randomUUID();
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":"%s","items":[{"productId":"%s","quantity":1}]}
                                """.formatted(otherCompanyId, productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()));

        assertThat(stub().countRequests("/companies/" + companyId + "/credit-limit")).isGreaterThan(0);
        assertThat(stub().countRequests("/companies/" + otherCompanyId + "/credit-limit")).isZero();
    }

    @Test
    void getByIdScopesVisibilityByCompanyAndCollapsesUnauthorizedCompanyToTheSame404AsNonexistent() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        String sellerToken = TestJwt.sellerAdminToken();
        String otherBuyerToken = TestJwt.buyerToken(otherCompanyId);

        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-P2", "Parafuso 2", new BigDecimal("50.00"), "ACTIVE");

        MvcResult created = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(createdJson.get("id").asText());

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.decidedBy").value("SYSTEM"))
                .andExpect(jsonPath("$.items[0].productId").value(productId.toString()));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());

        MvcResult otherCompanyResult = mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + otherBuyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("order_not_found"))
                .andReturn();

        MvcResult nonExistentResult = mockMvc.perform(get("/orders/{orderId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("order_not_found"))
                .andReturn();

        // O corpo do 404 de um pedido de outra empresa é idêntico ao de um id inexistente — nunca
        // revela que o pedido existe (D-47).
        assertThat(otherCompanyResult.getResponse().getContentAsString())
                .isEqualTo(nonExistentResult.getResponse().getContentAsString());
    }

    @Test
    void createOrderIsForbiddenForSellerAdminAndUnauthorizedWithoutToken() throws Exception {
        UUID productId = UUID.randomUUID();
        String sellerToken = TestJwt.sellerAdminToken();
        String requestBody = """
                {"items":[{"productId":"%s","quantity":1}]}
                """.formatted(productId);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }
}

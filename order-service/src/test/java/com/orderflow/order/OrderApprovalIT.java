package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tracer da Task 1 (ORD-03, D-38, D-46): o SELLER_ADMIN aprova um pedido pendente, a decisão fica
 * registrada (quem, quando, por quê) e a exposição da empresa passa a contar o pedido aprovado
 * acima do limite — sem a aprovação consultar auth-service ou catalog-service.
 */
class OrderApprovalIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sellerAdminApprovesPendingOrderAndDecisionIsRecordedAndConsumesCredit() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        String sellerToken = TestJwt.sellerAdminToken(sellerId);

        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-A1", "Produto A1", new BigDecimal("1500.00"), "ACTIVE");

        // 1 unidade a 1500.00 > limite 1000.00 -> PENDING_APPROVAL.
        MvcResult createdResult = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(createdJson.get("id").asText());
        OffsetDateTime createdAt = OffsetDateTime.parse(createdJson.get("createdAt").asText());

        stub().resetRecordedRequests();

        MvcResult approveResult = mockMvc.perform(post("/orders/{orderId}/approve", orderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"cliente estratégico"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(sellerId.toString()))
                .andExpect(jsonPath("$.reason").value("cliente estratégico"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andReturn();
        JsonNode approveJson = objectMapper.readTree(approveResult.getResponse().getContentAsString());
        OffsetDateTime decidedAt = OffsetDateTime.parse(approveJson.get("decidedAt").asText());
        assertThat(decidedAt).isAfterOrEqualTo(createdAt);

        // A aprovação não consultou nem o catalog-service nem o auth-service (D-38).
        assertThat(stub().requests()).isEmpty();

        // GET /orders/{id} pelo BUYER dono mostra a mesma decisão.
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(sellerId.toString()))
                .andExpect(jsonPath("$.reason").value("cliente estratégico"));

        // Novo pedido de 100.00 na mesma empresa: 1500.00 aprovados + 100.00 > 1000.00 ->
        // PENDING_APPROVAL — o pedido aprovado manualmente acima do limite consome crédito (D-38).
        UUID smallProductId = UUID.randomUUID();
        stub().registerProduct(smallProductId, "SKU-A2", "Produto A2", new BigDecimal("100.00"), "ACTIVE");
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(smallProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Aprovar sem corpo num pedido pendente -> 200 com reason nulo.
        MvcResult secondCreated = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(smallProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        JsonNode secondCreatedJson = objectMapper.readTree(secondCreated.getResponse().getContentAsString());
        UUID secondOrderId = UUID.fromString(secondCreatedJson.get("id").asText());

        mockMvc.perform(post("/orders/{orderId}/approve", secondOrderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        // Aprovar um pedido já aprovado automaticamente -> 409 order_not_pending, decidedBy segue SYSTEM.
        UUID autoApprovedProductId = UUID.randomUUID();
        stub().registerProduct(autoApprovedProductId, "SKU-A3", "Produto A3", new BigDecimal("10.00"), "ACTIVE");
        UUID smallLimitCompanyId = UUID.randomUUID();
        stub().registerCreditLimit(smallLimitCompanyId, new BigDecimal("1000.00"));
        String smallLimitBuyerToken = TestJwt.buyerToken(smallLimitCompanyId);
        MvcResult autoApprovedResult = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + smallLimitBuyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(autoApprovedProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("SYSTEM"))
                .andReturn();
        JsonNode autoApprovedJson = objectMapper.readTree(autoApprovedResult.getResponse().getContentAsString());
        UUID autoApprovedOrderId = UUID.fromString(autoApprovedJson.get("id").asText());

        mockMvc.perform(post("/orders/{orderId}/approve", autoApprovedOrderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("order_not_pending"));

        mockMvc.perform(get("/orders/{orderId}", autoApprovedOrderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedBy").value("SYSTEM"));
    }
}

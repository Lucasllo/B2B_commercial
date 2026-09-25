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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 1 (ORD-03, D-38, D-46): o SELLER_ADMIN aprova um pedido pendente, a decisão fica registrada
 * (quem, quando, por quê) e a exposição da empresa passa a contar o pedido aprovado acima do
 * limite — sem a aprovação consultar auth-service ou catalog-service. Task 2 (D-37, D-46): a
 * rejeição exige motivo, e decisão fora de PENDING_APPROVAL ou por quem não é vendedor é recusada
 * sem tocar na trilha de auditoria.
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

    @Test
    void sellerRejectsWithMandatoryReasonAndDecisionsOutsideTheWindowOrByTheWrongRoleAreRefused() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        String sellerToken = TestJwt.sellerAdminToken(sellerId);
        stub().registerCreditLimit(companyId, new BigDecimal("100.00"));

        // Pedido acima do limite (150.00 > 100.00) -> PENDING_APPROVAL, depois rejeitado.
        UUID rejectedProductId = UUID.randomUUID();
        stub().registerProduct(rejectedProductId, "SKU-R1", "Produto R1", new BigDecimal("150.00"), "ACTIVE");
        UUID rejectedOrderId = createPendingOrder(buyerToken, rejectedProductId);

        MvcResult rejectResult = mockMvc.perform(post("/orders/{orderId}/reject", rejectedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"sem histórico de pagamento"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value(sellerId.toString()))
                .andExpect(jsonPath("$.reason").value("sem histórico de pagamento"))
                .andExpect(jsonPath("$.decidedAt").exists())
                .andReturn();
        JsonNode rejectJson = objectMapper.readTree(rejectResult.getResponse().getContentAsString());
        String rejectedDecidedAt = rejectJson.get("decidedAt").asText();
        String rejectedReason = rejectJson.get("reason").asText();

        // O pedido rejeitado não consome crédito: novo pedido de 100.00 cabe exatamente no limite
        // de 100.00 (exposição continua zero) -> APPROVED.
        UUID fittingProductId = UUID.randomUUID();
        stub().registerProduct(fittingProductId, "SKU-R2", "Produto R2", new BigDecimal("100.00"), "ACTIVE");
        MvcResult approvedResult = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(fittingProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        JsonNode approvedJson = objectMapper.readTree(approvedResult.getResponse().getContentAsString());
        UUID approvedOrderId = UUID.fromString(approvedJson.get("id").asText());

        // Entrada malformada num pedido ainda pendente: continua PENDING_APPROVAL em todos os casos.
        UUID malformedProductId = UUID.randomUUID();
        stub().registerProduct(malformedProductId, "SKU-R3", "Produto R3", new BigDecimal("150.00"), "ACTIVE");
        UUID malformedOrderId = createPendingOrder(buyerToken, malformedProductId);
        String longReason = "a".repeat(501);

        mockMvc.perform(post("/orders/{orderId}/reject", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        mockMvc.perform(post("/orders/{orderId}/reject", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.reason").exists());

        mockMvc.perform(post("/orders/{orderId}/reject", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.reason").exists());

        mockMvc.perform(post("/orders/{orderId}/reject", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", longReason))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.reason").exists());

        mockMvc.perform(post("/orders/{orderId}/approve", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", longReason))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.reason").exists());

        mockMvc.perform(get("/orders/{orderId}", malformedOrderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Decidir de novo um pedido REJECTED, e rejeitar um pedido APPROVED -> 409 order_not_pending,
        // sem alterar decidedBy/decidedAt/reason.
        mockMvc.perform(post("/orders/{orderId}/approve", rejectedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"tentativa de sobrescrever"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("order_not_pending"));

        mockMvc.perform(post("/orders/{orderId}/reject", rejectedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"tentativa de sobrescrever"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("order_not_pending"));

        mockMvc.perform(get("/orders/{orderId}", rejectedOrderId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy").value(sellerId.toString()))
                .andExpect(jsonPath("$.decidedAt").value(rejectedDecidedAt))
                .andExpect(jsonPath("$.reason").value(rejectedReason));

        mockMvc.perform(post("/orders/{orderId}/reject", approvedOrderId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"tarde demais"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("order_not_pending"));

        // BUYER — inclusive o dono do pedido — não decide: 403 nos dois endpoints; sem token, 401;
        // id inexistente, 404; id que não é UUID, 400 invalid_parameter.
        UUID forbiddenProductId = UUID.randomUUID();
        stub().registerProduct(forbiddenProductId, "SKU-R4", "Produto R4", new BigDecimal("150.00"), "ACTIVE");
        UUID forbiddenOrderId = createPendingOrder(buyerToken, forbiddenProductId);

        mockMvc.perform(post("/orders/{orderId}/approve", forbiddenOrderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(post("/orders/{orderId}/reject", forbiddenOrderId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"não deveria funcionar"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(post("/orders/{orderId}/approve", forbiddenOrderId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/orders/{orderId}/approve", UUID.randomUUID())
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("order_not_found"));

        mockMvc.perform(post("/orders/{orderId}/approve", "nao-e-uuid")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));
    }

    private UUID createPendingOrder(String buyerToken, UUID productId) throws Exception {
        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }
}

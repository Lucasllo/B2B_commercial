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
}

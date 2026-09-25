package com.orderflow.order;

import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endurece a criação de pedido além do caminho feliz provado por {@link OrderControllerIT}
 * (plano {@code 04-01}): tudo ou nada (D-44), falha fechada de vizinho (D-39, D-41), limites de
 * entrada, tokens adversariais e o snapshot congelado (D-43) — plano {@code 04-02}.
 */
class OrderCreationEdgeCasesIT extends AbstractIntegrationTest {

    @Test
    void duplicateProductIdInOrderIsRejectedWith400BeforeAnyCatalogCall() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                                """.formatted(productId, productId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.items").exists());

        assertThat(stub().requests()).isEmpty();
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void mixOfValidAndInvalidItemsRejectsWholeOrderListingAllInvalidIdsInOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));

        UUID validA = UUID.randomUUID();
        UUID nonexistentX = UUID.randomUUID();
        UUID validB = UUID.randomUUID();
        UUID discontinuedD = UUID.randomUUID();

        stub().registerProduct(validA, "SKU-A", "Produto A", new BigDecimal("10.00"), "ACTIVE");
        stub().registerProduct(validB, "SKU-B", "Produto B", new BigDecimal("20.00"), "ACTIVE");
        stub().registerProduct(discontinuedD, "SKU-D", "Produto D", new BigDecimal("30.00"), "DISCONTINUED");
        // nonexistentX nunca é registrado — o stub devolve 404 (produto inexistente).

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1}
                                ]}
                                """.formatted(validA, nonexistentX, validB, discontinuedD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_order_items"))
                .andExpect(jsonPath("$.productIds.length()").value(2))
                .andExpect(jsonPath("$.productIds[0]").value(nonexistentX.toString()))
                .andExpect(jsonPath("$.productIds[1]").value(discontinuedD.toString()))
                .andReturn();

        assertThat(stub().countRequests("/products/")).isEqualTo(4);
        assertThat(stub().countRequests("/companies/")).isZero();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("http://", "127.0.0.1", "Exception", "at com.orderflow");

        assertOrderRowCount(companyId, 0);
    }

    private void assertOrderRowCount(UUID companyId, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".orders WHERE company_id = ?", Integer.class, companyId);
        assertThat(count).isEqualTo(expected);
    }
}

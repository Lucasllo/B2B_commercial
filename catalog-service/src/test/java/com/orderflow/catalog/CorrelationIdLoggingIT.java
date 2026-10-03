package com.orderflow.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.catalog.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova D-93/D-96 no catalog-service contra Postgres real: o {@code X-Correlation-Id} que o
 * order-service repassa ao consultar produto aparece na linha INFO de acesso, o serviço não ecoa o
 * header e o healthcheck do compose não gera linha de acesso.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdLoggingIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getProductWithCorrelationIdLogsAnAccessLineCarryingThatIdAndDoesNotEchoTheHeader(CapturedOutput output)
            throws Exception {
        String seller = TestJwt.sellerAdminToken();
        String sku = "CID-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult created = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"%s","name":"Produto CID","description":"d","price":"10.00"}
                                """.formatted(sku)))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult result = mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(UUID.randomUUID()))
                        .header("X-Correlation-Id", "it-cat-cid-1"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNull();
        assertThat(output.getOut()).containsPattern(
                Pattern.compile("\\[it-cat-cid-1\\] .*GET /products/" + productId + " -> 200"));
    }

    @Test
    void actuatorHealthDoesNotProduceAnAccessLine(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Correlation-Id", "it-cat-health-1"))
                .andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain("it-cat-health-1");
        assertThat(output.getOut()).doesNotContain("GET /actuator/health");
    }
}

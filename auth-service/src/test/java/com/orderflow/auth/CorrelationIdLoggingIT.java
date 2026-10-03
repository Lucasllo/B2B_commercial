package com.orderflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.auth.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova D-93/D-96 e T-07-19 no auth-service contra Postgres real: o {@code X-Correlation-Id} que o
 * order-service repassa na consulta de limite aparece na linha de acesso, e o login deixa só método,
 * caminho e status no log — nunca e-mail, senha ou token.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdLoggingIT extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@orderflow.local";
    private static final String ADMIN_PASSWORD = "ChangeMe!123";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void creditLimitLookupWithCorrelationIdLogsAnAccessLineCarryingThatId(CapturedOutput output) throws Exception {
        String seller = TestTokens.loginAndGetToken(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
        MvcResult created = mockMvc.perform(post("/companies")
                        .header("Authorization", "Bearer " + seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cid Ltda","creditLimit":"100.00",
                                 "buyerUser":{"email":"cid.buyer@cid.com","password":"Comprador!123"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String companyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        MvcResult result = mockMvc.perform(get("/companies/" + companyId + "/credit-limit")
                        .header("Authorization", "Bearer " + seller)
                        .header("X-Correlation-Id", "it-auth-cid-1"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isNull();
        assertThat(output.getOut()).containsPattern(Pattern.compile(
                "\\[it-auth-cid-1\\] .*GET /companies/" + companyId + "/credit-limit -> 200"));
    }

    @Test
    void loginLogsOnlyMethodPathAndStatusAndNeverCredentialsOrToken(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .header("X-Correlation-Id", "it-auth-cid-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = json.get("accessToken").asText();

        String out = output.getOut();
        assertThat(out).containsPattern(Pattern.compile("\\[it-auth-cid-2\\] .*POST /auth/login -> 200"));
        assertThat(out).doesNotContain(ADMIN_EMAIL);
        assertThat(out).doesNotContain(ADMIN_PASSWORD);
        assertThat(out).doesNotContain(accessToken);
    }
}

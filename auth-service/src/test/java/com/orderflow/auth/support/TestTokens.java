package com.orderflow.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.auth.auth.dto.LoginRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Helper de teste de integração: faz {@code POST /auth/login} de verdade e devolve o
 * {@code accessToken} do corpo, em vez de montar um JWT à mão — exercita o caminho real de
 * emissão de token que os testes precisam provar.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String loginAndGetToken(MockMvc mockMvc, ObjectMapper objectMapper, String email, String password)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }
}

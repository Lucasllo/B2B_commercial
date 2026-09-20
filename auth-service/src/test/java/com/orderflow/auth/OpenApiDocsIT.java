package com.orderflow.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que a Swagger UI e o spec OpenAPI 3 do auth-service estão acessíveis sem token, que o
 * esquema {@code bearerAuth} está declarado corretamente, e — o teste mais importante do arquivo —
 * que os novos caminhos liberados no {@code SecurityConfig} não ampliaram a superfície não
 * autenticada além do springdoc.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Test
    void specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OrderFlow — Auth Service API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void swaggerConfigUnderApiDocsIsAccessibleWithoutToken() throws Exception {
        // Prova que o padrão /v3/api-docs/** também está liberado, não só o caminho exato.
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiHtmlLetsSecurityChainPassWithoutToken() throws Exception {
        // Escrito com uma asserção genérica (diferente de 401), e não isOk()/is3xxRedirection():
        // o springdoc responde a este caminho com um redirect para /swagger-ui/index.html, e a
        // propriedade que interessa aqui é "a cadeia de segurança deixou passar", que continua
        // válida se uma versão futura servir a página direto.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void businessEndpointStillRequiresTokenGuardAgainstRegression() throws Exception {
        // Guarda de regressão: prova que os caminhos liberados para o springdoc não ampliaram a
        // superfície não autenticada além dele.
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}

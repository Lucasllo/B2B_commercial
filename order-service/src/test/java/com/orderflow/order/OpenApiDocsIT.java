package com.orderflow.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que a Swagger UI e o spec OpenAPI 3 do order-service estao acessiveis sem token, que o
 * esquema {@code bearerAuth} esta declarado corretamente, e — o teste mais importante do arquivo —
 * que os caminhos liberados no {@code SecurityConfig} para o springdoc nao ampliaram a superficie
 * nao autenticada alem dele. Espelha {@code notification-service}'s {@code OpenApiDocsIT}.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Test
    void specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OrderFlow — Order Service API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void swaggerConfigUnderApiDocsIsAccessibleWithoutToken() throws Exception {
        // Prova que o padrao /v3/api-docs/** tambem esta liberado, nao so o caminho exato.
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUiHtmlLetsSecurityChainPassWithoutToken() throws Exception {
        // Escrito com uma assercao generica (diferente de 401), e nao isOk()/is3xxRedirection():
        // o springdoc responde a este caminho com um redirect para /swagger-ui/index.html, e a
        // propriedade que interessa aqui e "a cadeia de seguranca deixou passar", que continua
        // valida se uma versao futura servir a pagina direto.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void businessEndpointStillRequiresTokenGuardAgainstRegression() throws Exception {
        // Guarda de regressao: prova que os caminhos liberados para o springdoc nao ampliaram a
        // superficie nao autenticada alem dele.
        mockMvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized());
    }
}

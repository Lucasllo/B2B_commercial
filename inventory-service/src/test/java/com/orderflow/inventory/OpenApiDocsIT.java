package com.orderflow.inventory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que a Swagger UI e o spec OpenAPI 3 do inventory-service estao acessiveis sem token, que
 * o esquema {@code bearerAuth} esta declarado corretamente, e — o teste mais importante do
 * arquivo — que os novos caminhos liberados no {@code SecurityConfig} nao ampliaram a superficie
 * nao autenticada alem do springdoc.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Test
    void specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OrderFlow — Inventory Service API"))
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
        // superficie nao autenticada alem dele. O 401 da cadeia de seguranca precede qualquer
        // consulta ao banco, entao o produto nao precisa existir.
        mockMvc.perform(get("/inventory/{productId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}

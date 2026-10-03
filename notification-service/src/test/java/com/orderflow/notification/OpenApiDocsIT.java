package com.orderflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que a Swagger UI e o spec OpenAPI 3 do notification-service estao acessiveis sem token,
 * que o esquema {@code bearerAuth} esta declarado corretamente, e — o teste mais importante do
 * arquivo — que os caminhos liberados no {@code SecurityConfig} para o springdoc nao ampliaram a
 * superficie nao autenticada alem dele. Espelha {@code inventory-service}'s {@code OpenApiDocsIT}.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Test
    void specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OrderFlow — Notification Service API"))
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
        // consulta ao DynamoDB, entao o produto nao precisa existir.
        mockMvc.perform(get("/notifications/{productId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serverUrlIsApiPrefix() throws Exception {
        assertThat(spec().path("servers").path(0).path("url").asText()).isEqualTo("/api");
    }

    @Test
    void bothHistoryOperationsDocumentSummaryTagAndRealErrors() throws Exception {
        JsonNode paths = spec().path("paths");

        JsonNode byProduct = operation(paths, "/notifications/{productId}", "get");
        JsonNode byOrder = operation(paths, "/notifications/orders/{orderId}", "get");

        for (JsonNode op : new JsonNode[] {byProduct, byOrder}) {
            assertThat(op.path("summary").asText()).isNotBlank();
            assertThat(op.path("tags").size()).isGreaterThan(0);
            JsonNode responses = op.path("responses");
            assertThat(responses.has("401")).isTrue();
            assertThat(responses.has("403")).isTrue();
            assertThat(responses.path("400").path("description").asText()).contains("invalid_identifier");
            assertThat(responses.path("503").path("description").asText())
                    .contains("notification_store_unavailable");
            responses.fields().forEachRemaining(entry -> {
                if (entry.getKey().startsWith("4") || entry.getKey().startsWith("5")) {
                    assertErrorResponseRef(entry.getValue(), entry.getKey());
                }
            });
        }
        assertThat(byOrder.path("responses").path("404").path("description").asText())
                .contains("order_not_found");
        assertErrorResponseRef(byOrder.path("responses").path("404"), "404");
    }

    @Test
    void errorResponseSchemaDeclaresErrorAndMessage() throws Exception {
        JsonNode properties = spec().path("components").path("schemas").path("ErrorResponse").path("properties");
        assertThat(properties.has("error")).isTrue();
        assertThat(properties.has("message")).isTrue();
    }

    @Test
    void notificationResponseDocumentsExamplesForEventTypeAndMessage() throws Exception {
        JsonNode properties = spec().path("components").path("schemas")
                .path("NotificationResponse").path("properties");
        assertThat(properties.path("eventType").hasNonNull("example")).isTrue();
        assertThat(properties.path("message").hasNonNull("example")).isTrue();
    }

    private JsonNode spec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return JsonMapper.builder().build().readTree(body);
    }

    private static JsonNode operation(JsonNode paths, String path, String method) {
        JsonNode operation = paths.path(path).path(method);
        assertThat(operation.isObject()).as(method.toUpperCase() + " " + path).isTrue();
        return operation;
    }

    private static void assertErrorResponseRef(JsonNode response, String label) {
        JsonNode content = response.path("content");
        assertThat(content.isObject()).as(label + " content").isTrue();
        boolean found = false;
        var mediaTypes = content.fields();
        while (mediaTypes.hasNext()) {
            String ref = mediaTypes.next().getValue().path("schema").path("$ref").asText("");
            if (ref.endsWith("/ErrorResponse")) {
                found = true;
            }
        }
        assertThat(found).as(label + " $ref /ErrorResponse").isTrue();
    }
}

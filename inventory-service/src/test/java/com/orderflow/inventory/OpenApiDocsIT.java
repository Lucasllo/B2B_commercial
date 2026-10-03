package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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

    @Test
    void serverUrlIsApiPrefix() throws Exception {
        assertThat(spec().path("servers").path(0).path("url").asText()).isEqualTo("/api");
    }

    @Test
    void specHasExactlyFourOperationsEachWithSummaryTagAndErrors() throws Exception {
        JsonNode paths = spec().path("paths");
        int[] count = {0};
        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
            if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                return;
            }
            count[0]++;
            String label = methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
            JsonNode operation = methodEntry.getValue();
            assertThat(operation.path("summary").asText()).as(label + " summary").isNotBlank();
            assertThat(operation.path("tags").size()).as(label + " tags").isGreaterThan(0);
            assertThat(operation.path("responses").has("401")).as(label + " 401").isTrue();
            operation.path("responses").fields().forEachRemaining(entry -> {
                if (entry.getKey().startsWith("4") || entry.getKey().startsWith("5")) {
                    assertErrorResponseRef(entry.getValue(), label + " " + entry.getKey());
                }
            });
        }));
        assertThat(count[0]).isEqualTo(4);
    }

    @Test
    void operationsDocumentRealErrors() throws Exception {
        JsonNode paths = spec().path("paths");

        JsonNode put = operation(paths, "/inventory/{productId}", "put");
        assertThat(put.path("responses").has("400")).isTrue();
        assertThat(put.path("responses").has("403")).isTrue();
        assertThat(put.path("responses").path("409").path("description").asText()).contains("stock_below_reserved");

        JsonNode reserve = operation(paths, "/inventory/{productId}/reservations", "post");
        assertThat(reserve.path("responses").has("404")).isTrue();
        assertThat(reserve.path("responses").path("409").path("description").asText()).contains("insufficient_stock");
        assertThat(reserve.path("responses").path("503").path("description").asText()).contains("reservation_conflict");

        JsonNode get = operation(paths, "/inventory/{productId}", "get");
        assertThat(get.path("responses").has("404")).isTrue();

        JsonNode release = operation(paths, "/inventory/{productId}/reservations/{reservationId}", "delete");
        assertThat(release.path("responses").has("403")).isTrue();
        assertThat(release.path("responses").has("404")).isTrue();
    }

    @Test
    void errorResponseSchemaDeclaresErrorMessageFieldsAvailableAndRequested() throws Exception {
        JsonNode properties = spec().path("components").path("schemas").path("ErrorResponse").path("properties");
        for (String name : List.of("error", "message", "fields", "available", "requested")) {
            assertThat(properties.has(name)).as(name).isTrue();
        }
    }

    @Test
    void stockResponseDocumentsEveryFieldWithExample() throws Exception {
        JsonNode properties = spec().path("components").path("schemas").path("StockResponse").path("properties");
        assertThat(properties.size()).isEqualTo(4);
        properties.fields().forEachRemaining(field ->
                assertThat(field.getValue().hasNonNull("example")).as(field.getKey() + " example").isTrue());
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

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace");
}

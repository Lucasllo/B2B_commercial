package com.orderflow.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.orderflow.catalog.product.ProductStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que a Swagger UI e o spec OpenAPI 3 do catalog-service estão acessíveis sem token, que o
 * esquema {@code bearerAuth} está declarado corretamente, e — o teste mais importante do arquivo —
 * que os novos caminhos liberados no {@code SecurityConfig} não ampliaram a superfície não
 * autenticada além do springdoc.
 */
class OpenApiDocsIT extends AbstractIntegrationTest {

    @Test
    void specJsonIsAccessibleWithoutTokenAndDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OrderFlow — Catalog Service API"))
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
        mockMvc.perform(get("/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serverUrlIsApiPrefix() throws Exception {
        assertThat(spec().path("servers").path(0).path("url").asText()).isEqualTo("/api");
    }

    @Test
    void everyOperationDocumentsSummaryTagAndRealErrors() throws Exception {
        JsonNode paths = spec().path("paths");
        assertThat(paths.isObject()).isTrue();
        assertThat(paths.fieldNames().hasNext()).isTrue();

        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                    return;
                }
                String label = methodEntry.getKey().toUpperCase() + " " + path;
                JsonNode operation = methodEntry.getValue();
                assertThat(operation.path("summary").asText()).as(label + " summary").isNotBlank();
                assertThat(operation.path("tags").size()).as(label + " tags").isGreaterThan(0);
                JsonNode responses = operation.path("responses");
                assertThat(responses.has("401")).as(label + " 401").isTrue();
                responses.fields().forEachRemaining(responseEntry -> {
                    if (responseEntry.getKey().startsWith("4")) {
                        assertErrorResponseRef(responseEntry.getValue(), label + " " + responseEntry.getKey());
                    }
                });
            });
        });

        JsonNode post = operation(paths, "/products", "post");
        assertThat(post.path("responses").has("403")).isTrue();
        assertThat(post.path("responses").path("409").path("description").asText()).contains("sku_already_used");

        JsonNode put = operation(paths, "/products/{productId}", "put");
        assertThat(put.path("responses").has("403")).isTrue();
        assertThat(put.path("responses").has("404")).isTrue();
        assertThat(put.path("responses").path("409").path("description").asText()).contains("sku_already_used");

        JsonNode status = operation(paths, "/products/{productId}/status", "put");
        assertThat(status.path("responses").has("403")).isTrue();
        assertThat(status.path("responses").has("404")).isTrue();

        JsonNode getById = operation(paths, "/products/{productId}", "get");
        assertThat(getById.path("responses").has("404")).isTrue();
    }

    @Test
    void errorResponseSchemaDeclaresErrorMessageAndFields() throws Exception {
        JsonNode properties = spec().path("components").path("schemas").path("ErrorResponse").path("properties");
        assertThat(properties.has("error")).isTrue();
        assertThat(properties.has("message")).isTrue();
        assertThat(properties.has("fields")).isTrue();
    }

    @Test
    void productResponseStatusEnumMatchesProductStatus() throws Exception {
        JsonNode enumNode = spec().path("components").path("schemas")
                .path("ProductResponse").path("properties").path("status").path("enum");
        List<String> actual = new ArrayList<>();
        enumNode.forEach(value -> actual.add(value.asText()));
        List<String> expected = new ArrayList<>();
        for (ProductStatus status : ProductStatus.values()) {
            expected.add(status.name());
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void createProductRequestPropertiesIncludeExamples() throws Exception {
        JsonNode properties = spec().path("components").path("schemas")
                .path("CreateProductRequest").path("properties");
        assertThat(properties.size()).isGreaterThan(0);
        properties.fields().forEachRemaining(field ->
                assertThat(field.getValue().hasNonNull("example"))
                        .as(field.getKey() + " example")
                        .isTrue());
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

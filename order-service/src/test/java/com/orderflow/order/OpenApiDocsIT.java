package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.orderflow.order.order.OrderStatus;
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

    @Test
    void serverUrlIsApiPrefix() throws Exception {
        assertThat(spec().path("servers").path(0).path("url").asText()).isEqualTo("/api");
    }

    @Test
    void orderStatusEnumInBothResponsesMatchesOrderStatusValues() throws Exception {
        List<String> expected = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            expected.add(status.name());
        }
        JsonNode schemas = spec().path("components").path("schemas");
        for (String schemaName : List.of("OrderResponse", "OrderSummaryResponse")) {
            List<String> actual = new ArrayList<>();
            schemas.path(schemaName).path("properties").path("status").path("enum")
                    .forEach(value -> actual.add(value.asText()));
            assertThat(actual).as(schemaName + ".status.enum").containsExactlyElementsOf(expected);
        }
    }

    @Test
    void createListAndGetOperationsDocumentRealErrors() throws Exception {
        JsonNode paths = spec().path("paths");

        JsonNode create = operation(paths, "/orders", "post");
        for (String code : List.of("400", "401", "403", "422", "503")) {
            assertThat(create.path("responses").has(code)).as("POST /orders " + code).isTrue();
        }
        assertThat(create.path("responses").path("422").path("description").asText()).contains("invalid_order_items");
        assertThat(create.path("responses").path("503").path("description").asText())
                .contains("catalog_service_unavailable");

        JsonNode getById = operation(paths, "/orders/{orderId}", "get");
        assertThat(getById.path("responses").has("401")).isTrue();
        assertThat(getById.path("responses").has("404")).isTrue();

        JsonNode list = operation(paths, "/orders", "get");
        assertThat(list.path("responses").has("401")).isTrue();
        assertThat(list.path("responses").path("400").path("description").asText()).contains("invalid_parameter");

        for (JsonNode operation : List.of(create, getById, list)) {
            operation.path("responses").fields().forEachRemaining(entry -> {
                if (entry.getKey().startsWith("4") || entry.getKey().startsWith("5")) {
                    assertErrorResponseRef(entry.getValue(), entry.getKey());
                }
            });
        }
    }

    @Test
    void errorResponseSchemaDeclaresErrorMessageFieldsAndProductIds() throws Exception {
        JsonNode properties = spec().path("components").path("schemas").path("ErrorResponse").path("properties");
        assertThat(properties.has("error")).isTrue();
        assertThat(properties.has("message")).isTrue();
        assertThat(properties.has("fields")).isTrue();
        assertThat(properties.has("productIds")).isTrue();
    }

    @Test
    void createOrderRequestDocumentsItemsWithExamples() throws Exception {
        JsonNode schemas = spec().path("components").path("schemas");
        JsonNode items = schemas.path("CreateOrderRequest").path("properties").path("items");
        assertThat(items.path("description").asText()).isNotBlank();
        JsonNode itemProps = schemas.path("OrderItemRequest").path("properties");
        assertThat(itemProps.path("productId").hasNonNull("example")).isTrue();
        assertThat(itemProps.path("quantity").hasNonNull("example")).isTrue();
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

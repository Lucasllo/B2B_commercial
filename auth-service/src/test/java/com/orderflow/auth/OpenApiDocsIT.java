package com.orderflow.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

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

    @Test
    void serverUrlIsApiPrefixAndJwksIsHidden() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.path("servers").path(0).path("url").asText()).isEqualTo("/api");
        assertThat(spec.path("paths").has("/.well-known/jwks.json")).isFalse();
    }

    @Test
    void loginIsPublicAndOtherOperationsInheritBearerAuth() throws Exception {
        JsonNode paths = spec().path("paths");
        JsonNode loginSecurity = paths.path("/auth/login").path("post").path("security");
        assertThat(loginSecurity.isArray()).isTrue();
        assertThat(loginSecurity).isEmpty();

        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
            if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                return;
            }
            boolean login = "/auth/login".equals(pathEntry.getKey()) && "post".equals(methodEntry.getKey());
            if (login) {
                return;
            }
            String label = methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
            assertThat(methodEntry.getValue().has("security")).as(label + " security").isFalse();
            assertThat(methodEntry.getValue().path("responses").has("401")).as(label + " 401").isTrue();
        }));
    }

    @Test
    void everyOperationDocumentsSummaryTagAndRealErrors() throws Exception {
        JsonNode paths = spec().path("paths");
        assertThat(paths.fieldNames().hasNext()).isTrue();
        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
            if (!HTTP_METHODS.contains(methodEntry.getKey())) {
                return;
            }
            String label = methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey();
            JsonNode operation = methodEntry.getValue();
            assertThat(operation.path("summary").asText()).as(label + " summary").isNotBlank();
            assertThat(operation.path("tags").size()).as(label + " tags").isGreaterThan(0);
            operation.path("responses").fields().forEachRemaining(responseEntry -> {
                if (responseEntry.getKey().startsWith("4")) {
                    assertErrorResponseRef(responseEntry.getValue(), label + " " + responseEntry.getKey());
                }
            });
        }));

        JsonNode login = operation(paths, "/auth/login", "post");
        assertThat(login.path("responses").has("400")).isTrue();
        assertThat(login.path("responses").has("401")).isTrue();

        JsonNode createCompany = operation(paths, "/companies", "post");
        assertThat(createCompany.path("responses").has("403")).isTrue();
        assertThat(createCompany.path("responses").path("409").path("description").asText())
                .contains("email_already_used");

        JsonNode getLimit = operation(paths, "/companies/{companyId}/credit-limit", "get");
        assertThat(getLimit.path("responses").has("403")).isTrue();
        assertThat(getLimit.path("responses").has("404")).isTrue();

        JsonNode putLimit = operation(paths, "/companies/{companyId}/credit-limit", "put");
        assertThat(putLimit.path("responses").has("400")).isTrue();
        assertThat(putLimit.path("responses").has("403")).isTrue();
        assertThat(putLimit.path("responses").has("404")).isTrue();
    }

    @Test
    void loginRequestEmailExampleIsDemoCredential() throws Exception {
        assertThat(spec().path("components").path("schemas").path("LoginRequest")
                .path("properties").path("email").path("example").asText())
                .isEqualTo("admin@orderflow.local");
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

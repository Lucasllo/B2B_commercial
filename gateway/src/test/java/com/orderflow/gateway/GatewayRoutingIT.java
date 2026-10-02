package com.orderflow.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.gateway.support.UpstreamStubServer;
import com.orderflow.gateway.support.UpstreamStubServer.CapturedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova o Gateway de verdade contra cinco upstreams HTTP (D-106, TEST-01, TEST-02): a tabela
 * de rotas do {@code application.yml} (URIs trocadas por {@link DynamicPropertySource}, sem
 * reescrever a lista), um único {@code X-Correlation-Id} no serviço e na resposta mesmo
 * quando o stub ecoa o header, a linha de acesso com o ID — exceto em {@code /actuator} — e a
 * Swagger UI única: o dropdown lista os cinco specs e cada {@code /docs/<svc>/v3/api-docs}
 * chega só no stub daquele serviço, já com {@code StripPrefix} nas rotas de negócio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingIT {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final UpstreamStubServer AUTH = new UpstreamStubServer("auth-service");
    private static final UpstreamStubServer CATALOG = new UpstreamStubServer("catalog-service");
    private static final UpstreamStubServer INVENTORY = new UpstreamStubServer("inventory-service");
    private static final UpstreamStubServer NOTIFICATION = new UpstreamStubServer("notification-service");
    private static final UpstreamStubServer ORDER = new UpstreamStubServer("order-service");

    private static final List<UpstreamStubServer> STUBS = List.of(AUTH, CATALOG, INVENTORY, NOTIFICATION, ORDER);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("orderflow.gateway.upstream.auth", AUTH::baseUrl);
        registry.add("orderflow.gateway.upstream.catalog", CATALOG::baseUrl);
        registry.add("orderflow.gateway.upstream.inventory", INVENTORY::baseUrl);
        registry.add("orderflow.gateway.upstream.notification", NOTIFICATION::baseUrl);
        registry.add("orderflow.gateway.upstream.order", ORDER::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        STUBS.forEach(UpstreamStubServer::stop);
    }

    @BeforeEach
    void resetStubs() {
        STUBS.forEach(UpstreamStubServer::reset);
    }

    @Test
    void validCorrelationIdReachesOrderOnceAndIsLogged(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send("/api/orders/abc", "X-Correlation-Id", "it-cid-123");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("X-Correlation-Id")).containsExactly("it-cid-123");
        assertThat(onlyOrderRequest().path()).isEqualTo("/orders/abc");
        assertThat(onlyOrderRequest().correlationIds()).containsExactly("it-cid-123");
        assertThat(output.toString()).contains("[it-cid-123]");
        assertThat(output.toString()).contains("GET /api/orders/abc -> 200");
    }

    @Test
    void lowercaseCorrelationHeaderReachesOrderOnce() throws Exception {
        HttpResponse<String> response = send("/api/orders/abc", "x-correlation-id", "it-cid-123");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("X-Correlation-Id")).containsExactly("it-cid-123");
        assertThat(onlyOrderRequest().correlationIds()).containsExactly("it-cid-123");
    }

    @Test
    void missingHeaderGeneratesTheSameNewUuidOnTheResponseAndTheStub() throws Exception {
        HttpResponse<String> response = send("/api/orders/abc");

        String id = singleResponseId(response);
        assertThat(id).matches(UUID_PATTERN);
        assertThat(onlyOrderRequest().correlationIds()).containsExactly(id);
    }

    @Test
    void invalidHeaderIsReplacedByTheSameNewUuidOnTheResponseAndTheStub() throws Exception {
        HttpResponse<String> response = send("/api/orders/abc", "X-Correlation-Id", "bad value!");

        String id = singleResponseId(response);
        assertThat(id).matches(UUID_PATTERN).isNotEqualTo("bad value!");
        assertThat(onlyOrderRequest().correlationIds()).containsExactly(id);
    }

    @Test
    void actuatorHealthIsNotWrittenAsAnAccessLine(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(output.toString()).doesNotContain("/actuator/health ->");
    }

    @Test
    void accessLogOmitsQueryStringAndAuthorization(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send(
                "/api/orders/abc?token=secret", "Authorization", "Bearer super-secret");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(onlyOrderRequest().path()).isEqualTo("/orders/abc");
        String logged = output.toString();
        assertThat(logged).contains("GET /api/orders/abc -> 200");
        assertThat(logged).doesNotContain("token=secret").doesNotContain("super-secret");
    }

    @Test
    void swaggerConfigListsExactlyTheFiveServiceSpecs() throws Exception {
        HttpResponse<String> response = send("/v3/api-docs/swagger-config");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode urls = json.get("urls");
        assertThat(urls).hasSize(5);
        List<String> services = List.of("auth", "catalog", "inventory", "notification", "order");
        for (int i = 0; i < services.size(); i++) {
            String service = services.get(i);
            assertThat(urls.get(i).get("name").asText()).isEqualTo(service + "-service");
            assertThat(urls.get(i).get("url").asText()).isEqualTo("/docs/" + service + "/v3/api-docs");
        }
        assertThat(json.path("urls.primaryName").asText()).isEqualTo("order-service");
    }

    @Test
    void swaggerUiHtmlRedirectsToTheIndex() throws Exception {
        HttpResponse<String> redirect = send("/swagger-ui.html");

        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location").orElse("")).endsWith("/swagger-ui/index.html");

        HttpResponse<String> index = send("/swagger-ui/index.html");
        assertThat(index.statusCode()).isEqualTo(200);
    }

    @Test
    void eachDocsRouteFetchesOnlyThatServiceSpec() throws Exception {
        record Spec(String service, UpstreamStubServer stub) {
        }
        List<Spec> specs = List.of(
                new Spec("auth", AUTH),
                new Spec("catalog", CATALOG),
                new Spec("inventory", INVENTORY),
                new Spec("notification", NOTIFICATION),
                new Spec("order", ORDER));
        for (Spec spec : specs) {
            STUBS.forEach(UpstreamStubServer::reset);
            HttpResponse<String> response = send("/docs/" + spec.service + "/v3/api-docs");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(response.body()).path("info").path("title").asText())
                    .isEqualTo(spec.service + "-service");
            assertThat(spec.stub.requests()).hasSize(1);
            assertThat(spec.stub.requests().getFirst().path()).isEqualTo("/v3/api-docs");
            for (UpstreamStubServer other : STUBS) {
                if (other != spec.stub) {
                    assertThat(other.requests()).isEmpty();
                }
            }
        }
    }

    @Test
    void businessRoutesStripTheApiPrefixOntoTheRightStub() throws Exception {
        assertRouted("/api/auth/login", AUTH, "/auth/login");
        assertRouted("/api/companies/x", AUTH, "/companies/x");
        assertRouted("/api/products/x", CATALOG, "/products/x");
        assertRouted("/api/inventory/x", INVENTORY, "/inventory/x");
        assertRouted("/api/notifications/x", NOTIFICATION, "/notifications/x");
        assertRouted("/api/orders/x", ORDER, "/orders/x");
    }

    private void assertRouted(String path, UpstreamStubServer stub, String upstreamPath) throws Exception {
        STUBS.forEach(UpstreamStubServer::reset);
        HttpResponse<String> response = send(path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(stub.requests()).hasSize(1);
        assertThat(stub.requests().getFirst().path()).isEqualTo(upstreamPath);
        for (UpstreamStubServer other : STUBS) {
            if (other != stub) {
                assertThat(other.requests()).isEmpty();
            }
        }
    }

    private CapturedRequest onlyOrderRequest() {
        assertThat(ORDER.requests()).hasSize(1);
        assertThat(AUTH.requests()).isEmpty();
        assertThat(CATALOG.requests()).isEmpty();
        assertThat(INVENTORY.requests()).isEmpty();
        assertThat(NOTIFICATION.requests()).isEmpty();
        return ORDER.requests().getFirst();
    }

    private static String singleResponseId(HttpResponse<String> response) {
        assertThat(response.headers().allValues("X-Correlation-Id")).hasSize(1);
        return response.headers().allValues("X-Correlation-Id").getFirst();
    }

    private HttpResponse<String> send(String path, String... headerNameAndValue) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (headerNameAndValue.length == 2) {
            builder.header(headerNameAndValue[0], headerNameAndValue[1]);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

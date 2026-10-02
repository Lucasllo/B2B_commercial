package com.orderflow.gateway;

import com.orderflow.gateway.support.UpstreamStubServer;
import com.orderflow.gateway.support.UpstreamStubServer.CapturedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * quando o stub ecoa o header, e a linha de acesso com o ID — exceto em {@code /actuator}.
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

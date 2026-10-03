package com.orderflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Métodos pequenos sobre {@link HttpClient} (JDK, {@code HTTP_1_1} — mesmo estilo dos clientes de
 * produção e de {@code ConcurrentRequests} do order-service) para os dois contextos reais deste
 * módulo — socket real, nunca {@code MockMvc} (os dois contextos sobem com servidor embutido de
 * verdade, D-68).
 */
public final class E2eHttp {

    public record Response(int status, JsonNode body) {

        public String text(String field) {
            JsonNode node = body.get(field);
            return (node == null || node.isNull()) ? null : node.asText();
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private E2eHttp() {
    }

    public static Response get(String url, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        authorize(builder, bearerToken);
        return send(builder.build());
    }

    public static Response postJson(String url, String bearerToken, Object body) {
        return postJson(url, bearerToken, body, Map.of());
    }

    public static Response postJson(String url, String bearerToken, Object body, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        authorize(builder, bearerToken);
        return send(builder.build());
    }

    public static Response put(String url, String bearerToken, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(body)));
        authorize(builder, bearerToken);
        return send(builder.build());
    }

    private static void authorize(HttpRequest.Builder builder, String bearerToken) {
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String rawBody = response.body();
            JsonNode json = (rawBody == null || rawBody.isBlank())
                    ? OBJECT_MAPPER.nullNode()
                    : OBJECT_MAPPER.readTree(rawBody);
            return new Response(response.statusCode(), json);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("E2E HTTP call failed: " + request.uri(), e);
        }
    }

    private static String writeJson(Object body) {
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize E2E request body", e);
        }
    }
}

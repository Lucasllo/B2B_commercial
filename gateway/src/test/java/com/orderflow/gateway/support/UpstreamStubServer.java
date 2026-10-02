package com.orderflow.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Upstream HTTP de teste do Gateway (mesmo estilo do {@code DownstreamStubServer} do
 * e2e-tests: JDK puro, {@code 127.0.0.1}, porta 0, uma thread virtual por requisição).
 * Grava método, caminho e os valores de {@code X-Correlation-Id} de cada pedido, responde
 * {@code /v3/api-docs} com um spec mínimo cujo {@code info.title} é o nome do serviço, e
 * ecoa o header recebido — de propósito, para o teste travar a regressão de header
 * duplicado na resposta.
 */
public final class UpstreamStubServer {

    public record CapturedRequest(String method, String path, List<String> correlationIds) {
    }

    private final String serviceName;
    private final HttpServer httpServer;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    public UpstreamStubServer(String serviceName) {
        this.serviceName = serviceName;
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao subir o stub " + serviceName, e);
        }
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/", this::handle);
        httpServer.start();
    }

    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    public void stop() {
        httpServer.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        List<String> correlationIds = exchange.getRequestHeaders().get("X-Correlation-Id");
        if (correlationIds == null) {
            correlationIds = List.of();
        } else {
            correlationIds = List.copyOf(correlationIds);
        }
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path, correlationIds));

        byte[] body = ("/v3/api-docs".equals(path) ? apiDocs() : "{\"ok\":true}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        for (String correlationId : correlationIds) {
            exchange.getResponseHeaders().add("X-Correlation-Id", correlationId);
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private String apiDocs() {
        return "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"" + serviceName + "\",\"version\":\"1\"},\"paths\":{}}";
    }
}

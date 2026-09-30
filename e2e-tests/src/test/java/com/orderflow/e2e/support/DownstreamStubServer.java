package com.orderflow.e2e.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cópia enxuta do stub de {@code order-service} (Fase 4) — mesma técnica (JDK pura, {@link
 * HttpServer}, {@code 127.0.0.1}, porta 0, uma thread virtual por requisição), sem nenhum modo de
 * falha (não é exercitado por este módulo). Serve {@code GET /products/{id}} (catalog-service) e
 * {@code GET /companies/{id}/credit-limit} (auth-service) a partir de mapas concorrentes
 * preenchidos por {@link #registerProduct}/{@link #registerCreditLimit}, e também {@code GET
 * /.well-known/jwks.json}, devolvendo {@link E2eJwt#jwksJson()} — os dois resource servers reais
 * (order-service e inventory-service) usam o decoder de PRODUÇÃO apontado para este stub via
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}.
 */
public final class DownstreamStubServer {

    private record ProductRecord(UUID id, String sku, String name, BigDecimal price, String status) {
    }

    private record CreditLimitRecord(UUID companyId, BigDecimal creditLimit) {
    }

    // JsonGenerator.Feature garante notação decimal simples (100.00), mesmo padrão do stub de
    // order-service.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    private static final Pattern PRODUCT_PATH = Pattern.compile("^/products/([0-9a-fA-F-]{36})$");
    private static final Pattern CREDIT_LIMIT_PATH = Pattern.compile("^/companies/([0-9a-fA-F-]{36})/credit-limit$");

    private final HttpServer httpServer;
    private final Map<UUID, ProductRecord> products = new ConcurrentHashMap<>();
    private final Map<UUID, CreditLimitRecord> creditLimits = new ConcurrentHashMap<>();

    public DownstreamStubServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start DownstreamStubServer", e);
        }
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/products", this::handleProducts);
        httpServer.createContext("/companies", this::handleCompanies);
        httpServer.createContext("/.well-known/jwks.json", this::handleJwks);
        httpServer.start();
    }

    public void registerProduct(UUID id, String sku, String name, BigDecimal price, String status) {
        products.put(id, new ProductRecord(id, sku, name, price, status));
    }

    public void registerCreditLimit(UUID companyId, BigDecimal creditLimit) {
        creditLimits.put(companyId, new CreditLimitRecord(companyId, creditLimit));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private void handleProducts(HttpExchange exchange) throws IOException {
        Matcher matcher = PRODUCT_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) {
            sendJson(exchange, 404, Map.of("error", "product_not_found", "message", "Product not found"));
            return;
        }
        UUID productId = UUID.fromString(matcher.group(1));
        ProductRecord product = products.get(productId);
        if (product == null || "DISCONTINUED".equals(product.status())) {
            sendJson(exchange, 404, Map.of("error", "product_not_found", "message", "Product not found"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", product.id().toString());
        body.put("sku", product.sku());
        body.put("name", product.name());
        body.put("description", null);
        body.put("price", product.price());
        body.put("status", product.status());
        body.put("createdAt", OffsetDateTime.now().toString());
        sendJson(exchange, 200, body);
    }

    private void handleCompanies(HttpExchange exchange) throws IOException {
        Matcher matcher = CREDIT_LIMIT_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) {
            sendJson(exchange, 404, Map.of("error", "company_not_found", "message", "Company not found"));
            return;
        }
        UUID companyId = UUID.fromString(matcher.group(1));

        CreditLimitRecord creditLimit = creditLimits.get(companyId);
        if (creditLimit == null) {
            sendJson(exchange, 404, Map.of("error", "company_not_found", "message", "Company not found"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyId", creditLimit.companyId().toString());
        body.put("creditLimit", creditLimit.creditLimit());
        sendJson(exchange, 200, body);
    }

    private void handleJwks(HttpExchange exchange) throws IOException {
        sendRaw(exchange, 200, E2eJwt.jwksJson());
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] payload = OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private void sendRaw(HttpExchange exchange, int statusCode, String rawBody) throws IOException {
        byte[] payload = rawBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}

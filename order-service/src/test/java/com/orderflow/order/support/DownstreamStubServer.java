package com.orderflow.order.support;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stub HTTP da própria JDK ({@link HttpServer}, {@code 127.0.0.1}, porta 0, executor de uma
 * thread virtual por requisição) para catalog-service e auth-service — serve {@code GET
 * /products/{id}} e {@code GET /companies/{id}/credit-limit} a partir de mapas concorrentes
 * preenchidos por {@link #registerProduct} e {@link #registerCreditLimit}.
 *
 * <p>Diverge da pesquisa da fase, que recomendou {@code MockRestServiceServer}: aquele mock
 * substitui a fábrica de requests do cliente sob teste, então os timeouts configurados em {@code
 * ClientConfig} nunca seriam exercitados e o repasse do header {@code Authorization} não passaria
 * por socket. Este stub usa só a JDK (nenhuma dependência nova, mesma premissa da pesquisa) e
 * serve igualmente aos testes com {@code MockMvc} (Task 1) e aos de socket real (Task 2) — a
 * divergência e o motivo estão registrados no SUMMARY.
 */
public final class DownstreamStubServer {

    private record ProductRecord(UUID id, String sku, String name, BigDecimal price, String status) {
    }

    private record CreditLimitRecord(UUID companyId, BigDecimal creditLimit) {
    }

    public record RecordedRequest(String method, String path, String authorizationHeader) {
    }

    // JsonGenerator.Feature (não SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, depreciado) —
    // garante notação decimal simples (100.00) mesmo que um valor futuro de teste caia fora da
    // faixa em que BigDecimal.toString() já produz forma plana por si só.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    private static final Pattern PRODUCT_PATH = Pattern.compile("^/products/([0-9a-fA-F-]{36})$");
    private static final Pattern CREDIT_LIMIT_PATH = Pattern.compile("^/companies/([0-9a-fA-F-]{36})/credit-limit$");

    private final HttpServer httpServer;
    private final Map<UUID, ProductRecord> products = new ConcurrentHashMap<>();
    private final Map<UUID, CreditLimitRecord> creditLimits = new ConcurrentHashMap<>();
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    public DownstreamStubServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start DownstreamStubServer", e);
        }
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.createContext("/products", this::handleProducts);
        httpServer.createContext("/companies", this::handleCompanies);
        httpServer.start();
    }

    public void registerProduct(UUID id, String sku, String name, BigDecimal price, String status) {
        products.put(id, new ProductRecord(id, sku, name, price, status));
    }

    public void registerCreditLimit(UUID companyId, BigDecimal creditLimit) {
        creditLimits.put(companyId, new CreditLimitRecord(companyId, creditLimit));
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(recordedRequests);
    }

    public long countRequests(String pathPrefix) {
        return recordedRequests.stream().filter(r -> r.path().startsWith(pathPrefix)).count();
    }

    public void resetRecordedRequests() {
        recordedRequests.clear();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    public void stop() {
        httpServer.stop(0);
    }

    private void handleProducts(HttpExchange exchange) throws IOException {
        record(exchange);
        Matcher matcher = PRODUCT_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) {
            sendJson(exchange, 404, Map.of("error", "product_not_found", "message", "Product not found"));
            return;
        }
        UUID productId = UUID.fromString(matcher.group(1));
        ProductRecord product = products.get(productId);
        if (product == null || "DISCONTINUED".equals(product.status())) {
            // Produto desconhecido ou DISCONTINUED devolve o mesmo 404 — a visão de BUYER do
            // catálogo (D-24), que este stub reproduz.
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
        record(exchange);
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

    private void record(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        recordedRequests.add(new RecordedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(), authorization));
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] payload = OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}

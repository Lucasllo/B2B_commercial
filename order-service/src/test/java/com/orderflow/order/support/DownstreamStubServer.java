package com.orderflow.order.support;

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

    /**
     * Modos de falha de vizinho (04-02 Task 2): {@code SERVER_ERROR} devolve 500;
     * {@code SLOW} dorme 3 s antes de responder normalmente — mais que o timeout de leitura de 1 s
     * do perfil de teste ({@code application-test.yml}); {@code MALFORMED_BODY} devolve 200 com um
     * corpo que não é JSON válido; {@code WRONG_COMPANY} só se aplica à consulta de limite de
     * crédito — 200 com um {@code companyId} diferente do pedido, tratado como resposta não
     * confiável pelo cliente (D-39).
     */
    public enum Failure {
        SERVER_ERROR, SLOW, MALFORMED_BODY, WRONG_COMPANY
    }

    private record ProductRecord(UUID id, String sku, String name, BigDecimal price, String status) {
    }

    private record CreditLimitRecord(UUID companyId, BigDecimal creditLimit) {
    }

    public record RecordedRequest(String method, String path, String authorizationHeader, String correlationIdHeader) {
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

    // Lidos a cada requisição (Task 2 <action>) — voláteis, nunca sincronizados, porque cada
    // requisição de teste roda numa thread virtual própria e o pior caso é uma corrida benigna
    // entre a chamada de setup e a primeira requisição HTTP do próprio teste.
    private volatile Failure catalogFailure;
    private volatile Failure authFailure;

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

    /**
     * Atualiza nome e preço de um produto já registrado, mantendo {@code sku}/{@code status} —
     * usado para provar que o pedido já criado não muda quando o catálogo muda depois (D-43).
     */
    public void updateProduct(UUID id, String name, BigDecimal price) {
        ProductRecord existing = products.get(id);
        if (existing == null) {
            throw new IllegalStateException("Product not registered: " + id);
        }
        products.put(id, new ProductRecord(id, existing.sku(), name, price, existing.status()));
    }

    public void failCatalogWith(Failure failure) {
        this.catalogFailure = failure;
    }

    public void failAuthWith(Failure failure) {
        this.authFailure = failure;
    }

    /** Chamado num {@code @AfterEach} — nenhum modo de falha vaza de um teste para o próximo. */
    public void clearFailures() {
        this.catalogFailure = null;
        this.authFailure = null;
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
        if (applyGenericFailure(exchange, catalogFailure)) {
            return;
        }
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
        if (applyGenericFailure(exchange, authFailure)) {
            return;
        }
        Matcher matcher = CREDIT_LIMIT_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) {
            sendJson(exchange, 404, Map.of("error", "company_not_found", "message", "Company not found"));
            return;
        }
        UUID companyId = UUID.fromString(matcher.group(1));

        if (authFailure == Failure.WRONG_COMPANY) {
            // Resposta 200 estruturalmente válida, mas para uma empresa diferente da pedida — o
            // cliente deve tratar como resposta não confiável, nunca como o limite real (D-39).
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("companyId", UUID.randomUUID().toString());
            body.put("creditLimit", new BigDecimal("999999.99"));
            sendJson(exchange, 200, body);
            return;
        }

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

    /**
     * Aplica {@code SERVER_ERROR}/{@code SLOW}/{@code MALFORMED_BODY} (comuns aos dois vizinhos).
     * {@code WRONG_COMPANY} não é genérico (só se aplica ao limite de crédito) e é tratado em
     * {@link #handleCompanies}. Devolve {@code true} quando a resposta já foi enviada — o chamador
     * não deve continuar o processamento normal.
     */
    private boolean applyGenericFailure(HttpExchange exchange, Failure failure) throws IOException {
        if (failure == Failure.SLOW) {
            sleepBeyondReadTimeout();
            return false;
        }
        if (failure == Failure.SERVER_ERROR) {
            sendJson(exchange, 500, Map.of("error", "internal_error", "message", "Simulated downstream failure"));
            return true;
        }
        if (failure == Failure.MALFORMED_BODY) {
            sendRaw(exchange, 200, "{not json");
            return true;
        }
        return false;
    }

    private void sleepBeyondReadTimeout() {
        try {
            // 3s > o timeout de leitura de 1s do perfil de teste (application-test.yml) — força o
            // cliente a estourar por timeout, nunca por resposta lenta-mas-dentro-do-prazo.
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        recordedRequests.add(new RecordedRequest(
                exchange.getRequestMethod(), exchange.getRequestURI().getPath(), authorization, correlationId));
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        byte[] payload = OBJECT_MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    /** {@code MALFORMED_BODY}: corpo literal, não passado pelo Jackson — não é JSON válido de propósito. */
    private void sendRaw(HttpExchange exchange, int statusCode, String rawBody) throws IOException {
        byte[] payload = rawBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}

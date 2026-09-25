package com.orderflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.orderflow.order.support.DownstreamStubServer.Failure;
import com.orderflow.order.support.TestJwt;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endurece a criação de pedido além do caminho feliz provado por {@link OrderControllerIT}
 * (plano {@code 04-01}): tudo ou nada (D-44), falha fechada de vizinho (D-39, D-41), limites de
 * entrada, tokens adversariais e o snapshot congelado (D-43) — plano {@code 04-02}.
 */
class OrderCreationEdgeCasesIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearDownstreamFailures() {
        stub().clearFailures();
    }

    @Test
    void duplicateProductIdInOrderIsRejectedWith400BeforeAnyCatalogCall() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":2}]}
                                """.formatted(productId, productId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.fields.items").exists());

        assertThat(stub().requests()).isEmpty();
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void mixOfValidAndInvalidItemsRejectsWholeOrderListingAllInvalidIdsInOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));

        UUID validA = UUID.randomUUID();
        UUID nonexistentX = UUID.randomUUID();
        UUID validB = UUID.randomUUID();
        UUID discontinuedD = UUID.randomUUID();

        stub().registerProduct(validA, "SKU-A", "Produto A", new BigDecimal("10.00"), "ACTIVE");
        stub().registerProduct(validB, "SKU-B", "Produto B", new BigDecimal("20.00"), "ACTIVE");
        stub().registerProduct(discontinuedD, "SKU-D", "Produto D", new BigDecimal("30.00"), "DISCONTINUED");
        // nonexistentX nunca é registrado — o stub devolve 404 (produto inexistente).

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1},
                                  {"productId":"%s","quantity":1}
                                ]}
                                """.formatted(validA, nonexistentX, validB, discontinuedD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_order_items"))
                .andExpect(jsonPath("$.productIds.length()").value(2))
                .andExpect(jsonPath("$.productIds[0]").value(nonexistentX.toString()))
                .andExpect(jsonPath("$.productIds[1]").value(discontinuedD.toString()))
                .andReturn();

        assertThat(stub().countRequests("/products/")).isEqualTo(4);
        assertThat(stub().countRequests("/companies/")).isZero();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("http://", "127.0.0.1", "Exception", "at com.orderflow");

        assertOrderRowCount(companyId, 0);
    }

    // ---- Task 2: vizinho fora do ar, lento ou quebrado falha fechado; tokens ruins são barrados ----

    @Test
    void catalogServiceServerErrorReturns503WithoutCallingAuthServiceOrSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().failCatalogWith(Failure.SERVER_ERROR);

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("catalog_service_unavailable"))
                .andReturn();

        assertThat(stub().countRequests("/companies/")).isZero();
        assertNoLeak(result.getResponse().getContentAsString());
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void catalogServiceMalformedBodyReturns503WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().failCatalogWith(Failure.MALFORMED_BODY);

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("catalog_service_unavailable"))
                .andReturn();

        assertNoLeak(result.getResponse().getContentAsString());
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void catalogServiceSlowReturns503WithinTwoAndAHalfSeconds() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().failCatalogWith(Failure.SLOW);

        long start = System.nanoTime();
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("catalog_service_unavailable"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2500);
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void authServiceServerErrorReturns503WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerProduct(productId, "SKU-AE1", "Produto AE1", new BigDecimal("10.00"), "ACTIVE");
        stub().failAuthWith(Failure.SERVER_ERROR);

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("auth_service_unavailable"))
                .andReturn();

        assertNoLeak(result.getResponse().getContentAsString());
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void authServiceMalformedBodyReturns503WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerProduct(productId, "SKU-AE2", "Produto AE2", new BigDecimal("10.00"), "ACTIVE");
        stub().failAuthWith(Failure.MALFORMED_BODY);

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("auth_service_unavailable"))
                .andReturn();

        assertNoLeak(result.getResponse().getContentAsString());
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void authServiceWrongCompanyInCreditLimitResponseReturns503WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerProduct(productId, "SKU-AE3", "Produto AE3", new BigDecimal("10.00"), "ACTIVE");
        stub().failAuthWith(Failure.WRONG_COMPANY);

        MvcResult result = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("auth_service_unavailable"))
                .andReturn();

        assertNoLeak(result.getResponse().getContentAsString());
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void authServiceSlowReturns503WithinTwoAndAHalfSeconds() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerProduct(productId, "SKU-AE4", "Produto AE4", new BigDecimal("10.00"), "ACTIVE");
        stub().failAuthWith(Failure.SLOW);

        long start = System.nanoTime();
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("auth_service_unavailable"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2500);
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void companyWithoutRegisteredCreditLimitReturns503WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerProduct(productId, "SKU-AE5", "Produto AE5", new BigDecimal("10.00"), "ACTIVE");
        // companyId nunca registrado no stub — 404 do stub, tratado como indisponibilidade (D-39).

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("auth_service_unavailable"));

        assertOrderRowCount(companyId, 0);
    }

    @Test
    void tokenSignedByDifferentKeyReturns401Unauthorized() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey otherKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("other-key")
                .build();

        JWTClaimsSet claims = buyerClaims(UUID.randomUUID(), "orderflow-auth-service",
                new Date(), Date.from(Instant.now().plusSeconds(3600)));
        String token = TestJwt.tokenSignedBy(otherKey, claims);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void tokenWithExpirationInThePastReturns401Unauthorized() throws Exception {
        JWTClaimsSet claims = buyerClaims(UUID.randomUUID(), "orderflow-auth-service",
                Date.from(Instant.now().minusSeconds(7200)), Date.from(Instant.now().minusSeconds(3600)));
        String token = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, claims);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void tokenWithDifferentIssuerReturns401Unauthorized() throws Exception {
        JWTClaimsSet claims = buyerClaims(UUID.randomUUID(), "outro-emissor",
                new Date(), Date.from(Instant.now().plusSeconds(3600)));
        String token = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, claims);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void buyerTokenWithoutCompanyIdClaimReturns403ForbiddenWithoutCallingStub() throws Exception {
        stub().resetRecordedRequests();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "BUYER")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String token = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, claims);

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        assertThat(stub().requests()).isEmpty();
    }

    // ---- Task 3: limites de entrada, total fora da faixa, snapshot congelado, regra de crédito ----

    @Test
    void emptyItemsListIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected("{\"items\":[]}");
    }

    @Test
    void missingItemsFieldIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected("{}");
    }

    @Test
    void moreThan50ItemsIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected(itemsBody(51));
    }

    @Test
    void nullProductIdIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected("{\"items\":[{\"productId\":null,\"quantity\":1}]}");
    }

    @Test
    void nullQuantityIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected(
                "{\"items\":[{\"productId\":\"%s\",\"quantity\":null}]}".formatted(UUID.randomUUID()));
    }

    @Test
    void zeroQuantityIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected(quantityBody(0));
    }

    @Test
    void negativeQuantityIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected(quantityBody(-1));
    }

    @Test
    void quantityAboveMaxIsRejectedWith400WithoutCallingStub() throws Exception {
        assertStructurallyInvalidBodyRejected(quantityBody(1_000_001));
    }

    @Test
    void nonJsonBodyIsRejectedWith400MalformedRequestWithoutCallingStub() throws Exception {
        stub().resetRecordedRequests();
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed_request"));

        assertThat(stub().requests()).isEmpty();
    }

    @Test
    void exactly50DistinctItemsWithSufficientLimitIsCreated() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("100000.00"));

        List<UUID> productIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID productId = UUID.randomUUID();
            productIds.add(productId);
            stub().registerProduct(productId, "SKU-L" + i, "Produto L" + i, new BigDecimal("10.00"), "ACTIVE");
        }

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsBody(productIds)))
                .andExpect(status().isCreated());
    }

    @Test
    void totalThatDoesNotFitInColumnReturns422WithoutSavingOrder() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        // 99999999999999999.99 x 2 = 199999999999999999.98 — 18 dígitos inteiros, estoura os 17 de
        // NUMERIC(19,2); a checagem do limite de crédito nem chega a ser alcançada.
        stub().registerProduct(productId, "SKU-HUGE", "Produto caro",
                new BigDecimal("99999999999999999.99"), "ACTIVE");

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quantityItemBody(productId, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("order_total_out_of_range"));

        assertThat(stub().countRequests("/companies/")).isZero();
        assertOrderRowCount(companyId, 0);
    }

    @Test
    void orderSnapshotDoesNotChangeWhenCatalogProductChangesAfterCreation() throws Exception {
        stub().resetRecordedRequests();
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("1000.00"));
        stub().registerProduct(productId, "SKU-SNAP", "Parafuso", new BigDecimal("100.00"), "ACTIVE");

        MvcResult created = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleItemBody(productId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID orderId = extractId(created.getResponse().getContentAsString());

        // Produto muda de nome e preço DEPOIS de o pedido já existir — o snapshot gravado não pode
        // seguir essa mudança (D-43).
        stub().updateProduct(productId, "Parafuso Novo", new BigDecimal("999.00"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(100.00))
                .andExpect(jsonPath("$.items[0].name").value("Parafuso"))
                .andExpect(jsonPath("$.items[0].subtotal").value(100.00));
    }

    private void assertStructurallyInvalidBodyRejected(String body) throws Exception {
        stub().resetRecordedRequests();
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));

        assertThat(stub().requests()).isEmpty();
    }

    private String quantityBody(int quantity) {
        return quantityItemBody(UUID.randomUUID(), quantity);
    }

    private String quantityItemBody(UUID productId, int quantity) {
        return "{\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}".formatted(productId, quantity);
    }

    /** {@code count} itens distintos, quantidade 1 cada — usado para os limites de tamanho da lista. */
    private String itemsBody(int count) {
        return itemsBody(IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList());
    }

    private String itemsBody(List<UUID> productIds) {
        String items = productIds.stream()
                .map(id -> "{\"productId\":\"%s\",\"quantity\":1}".formatted(id))
                .collect(Collectors.joining(","));
        return "{\"items\":[" + items + "]}";
    }

    private UUID extractId(String responseBody) throws Exception {
        JsonNode json = objectMapper.readTree(responseBody);
        return UUID.fromString(json.get("id").asText());
    }

    private JWTClaimsSet buyerClaims(UUID companyId, String issuer, Date issueTime, Date expirationTime) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .claim("role", "BUYER")
                .claim("company_id", companyId.toString())
                .issueTime(issueTime)
                .expirationTime(expirationTime)
                .build();
    }

    private String singleItemBody(UUID productId) {
        return """
                {"items":[{"productId":"%s","quantity":1}]}
                """.formatted(productId);
    }

    private void assertNoLeak(String body) {
        assertThat(body).doesNotContain("http://", "127.0.0.1", "Exception", "at com.orderflow");
    }

    private void assertOrderRowCount(UUID companyId, int expected) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"order\".orders WHERE company_id = ?", Integer.class, companyId);
        assertThat(count).isEqualTo(expected);
    }
}

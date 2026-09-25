package com.orderflow.order;

import com.nimbusds.jose.jwk.RSAKey;
import com.orderflow.order.support.DownstreamStubServer.Failure;
import com.orderflow.order.support.TestJwt;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endurece a criação de pedido além do caminho feliz provado por {@link OrderControllerIT}
 * (plano {@code 04-01}): tudo ou nada (D-44), falha fechada de vizinho (D-39, D-41), limites de
 * entrada, tokens adversariais e o snapshot congelado (D-43) — plano {@code 04-02}.
 */
class OrderCreationEdgeCasesIT extends AbstractIntegrationTest {

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

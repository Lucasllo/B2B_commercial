package com.orderflow.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.catalog.product.ProductRepository;
import com.orderflow.catalog.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova de CAT-01 e CAT-02 contra PostgreSQL real. Task 2 (tracer): o vendedor cria um produto
 * real e o lê de volta. Task 3 acrescenta atualização, retirada por status e listagem paginada
 * por papel.
 */
class ProductControllerIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    private String createProductPayload(String sku, String name, String description, String price) {
        return """
                {"sku":"%s","name":"%s","description":"%s","price":"%s"}
                """.formatted(sku, name, description, price);
    }

    private static String uniqueSku(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // -----------------------------------------------------------------------------------------
    // Task 2 (CAT-01, tracer): criação e leitura de um produto real, do Flyway ao JWT.
    // -----------------------------------------------------------------------------------------

    @Test
    void createProductWithSellerAdminTokenReturns201WithLocationAndExpectedBody() throws Exception {
        String token = TestJwt.sellerAdminToken();
        String sku = uniqueSku("SKU");

        MvcResult result = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, "Cafe Torrado 1kg", "Pacote 1kg", "29.90")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.name").value("Cafe Torrado 1kg"))
                .andExpect(jsonPath("$.price").value(29.90))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        String productId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).endsWith(productId);
    }

    @Test
    void createProductIncreasesRowCountByOneWithActiveStatusAndExactPrice() throws Exception {
        String token = TestJwt.sellerAdminToken();
        long before = productRepository.count();
        String sku = uniqueSku("SKU");

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, "Contagem Produto", "desc", "29.90")))
                .andExpect(status().isCreated());

        assertThat(productRepository.count()).isEqualTo(before + 1);
        var saved = productRepository.findAll().stream()
                .filter(p -> p.getSku().equals(sku))
                .findFirst()
                .orElseThrow();
        assertThat(saved.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(saved.getPrice()).isEqualByComparingTo(new java.math.BigDecimal("29.90"));
    }

    @Test
    void getProductByIdWithSellerAdminTokenReturnsExactlyCreatedValues() throws Exception {
        String token = TestJwt.sellerAdminToken();
        String sku = uniqueSku("SKU");

        MvcResult creation = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, "Produto Leitura", "descricao leitura", "15.00")))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.name").value("Produto Leitura"))
                .andExpect(jsonPath("$.description").value("descricao leitura"))
                .andExpect(jsonPath("$.price").value(15.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getProductByRandomIdReturns404WithErrorAndMessageKeys() throws Exception {
        String token = TestJwt.sellerAdminToken();
        String randomId = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(get("/products/" + randomId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.has("error")).isTrue();
        assertThat(json.has("message")).isTrue();
    }

    @Test
    void createProductIgnoresClientSuppliedStatusAndAlwaysCreatesActive() throws Exception {
        String token = TestJwt.sellerAdminToken();
        String sku = uniqueSku("SKU");
        String payload = """
                {"sku":"%s","name":"Papel Ignorado","description":"desc","price":"10.00","status":"DISCONTINUED"}
                """.formatted(sku);

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createProductWithThreeDecimalPriceReturns400AndDoesNotCreateRow() throws Exception {
        String token = TestJwt.sellerAdminToken();
        long before = productRepository.count();
        String sku = uniqueSku("SKU");

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, "Tres Casas", "desc", "29.905")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.price").isNotEmpty());

        assertThat(productRepository.count()).isEqualTo(before);
    }

    @Test
    void createProductWithNegativePriceReturns400AndZeroPriceReturns201() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "Negativo", "desc", "-0.01")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "Zero", "desc", "0.00")))
                .andExpect(status().isCreated());
    }

    @Test
    void createProductWithBlankSkuOrNameReturns400WithFieldErrors() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload("", "Nome Ok", "desc", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.sku").isNotEmpty());

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "", "desc", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").isNotEmpty());
    }

    @Test
    void createAndGetProductWithoutAuthorizationHeaderReturn401() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "Sem Token", "desc", "10.00")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/products/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProductWithBuyerTokenReturns403AndDoesNotInsertRow() throws Exception {
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        long before = productRepository.count();

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "Nao Deveria Existir", "desc", "10.00")))
                .andExpect(status().isForbidden());

        assertThat(productRepository.count()).isEqualTo(before);
    }

    @Test
    void getProductWithTokenSignedByDifferentKeyReturns401() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey otherKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("other-key")
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String tokenSignedByOtherKey = signWithKey(claims, otherKey);

        mockMvc.perform(get("/products/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenSignedByOtherKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProductWithExpiredTokenReturns401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build();
        String expiredToken = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, claims);

        mockMvc.perform(get("/products/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    private static String signWithKey(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .build();
        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(signingKey));
        return signedJWT.serialize();
    }

    // -----------------------------------------------------------------------------------------
    // Task 3: atualização (CAT-01), retirada por status (D-23) e listagem por papel/paginação
    // (CAT-02, D-24, D-26).
    // -----------------------------------------------------------------------------------------

    private String createProductAndReturnId(String sku, String name, String description, String price) throws Exception {
        String token = TestJwt.sellerAdminToken();
        MvcResult result = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, name, description, price)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void updateProductWithSellerAdminTokenReturns200AndSubsequentGetReturnsNewValuesWithSkuUnchanged() throws Exception {
        String sku = uniqueSku("SKU");
        String productId = createProductAndReturnId(sku, "Cafe Torrado 1kg", "Pacote 1kg", "29.90");
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/products/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cafe Torrado 500g","description":"Pacote 500g","price":"18.50"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cafe Torrado 500g"))
                .andExpect(jsonPath("$.description").value("Pacote 500g"))
                .andExpect(jsonPath("$.price").value(18.50))
                .andExpect(jsonPath("$.sku").value(sku));
    }

    @Test
    void updateProductWithThreeDecimalPriceReturns400AndPreviousPricePersists() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Tres Casas", "desc", "10.00");
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/products/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nome Novo","description":"desc novo","price":"18.505"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(10.00));
    }

    @Test
    void updateProductWithBuyerTokenReturns403AndPreviousValuesPersist() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Buyer Update", "desc", "10.00");
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());

        mockMvc.perform(put("/products/" + productId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nao Deveria Mudar","description":"desc","price":"1.00"}
                                """))
                .andExpect(status().isForbidden());

        String sellerToken = TestJwt.sellerAdminToken();
        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Produto Buyer Update"));
    }

    @Test
    void updateProductWithRandomIdReturns404() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/products/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nome","description":"desc","price":"1.00"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeStatusToDiscontinuedReturns200AndNeverDeletesRow() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Status", "desc", "10.00");
        String token = TestJwt.sellerAdminToken();
        long countBefore = productRepository.count();

        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCONTINUED"));

        assertThat(productRepository.count()).isEqualTo(countBefore);
        assertThat(productRepository.findById(UUID.fromString(productId))).isPresent();
    }

    @Test
    void changeStatusBackToActiveMakesProductReappearInBuyerListing() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Reativado", "desc", "10.00");
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isOk());

        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        MvcResult listing = mockMvc.perform(get("/products?page=0&size=100")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listing.getResponse().getContentAsString()).contains(productId);
    }

    @Test
    void changeStatusWithUnknownValueReturns400() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Status Invalido", "desc", "10.00");
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"NAO_EXISTE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeStatusWithBuyerTokenReturns403() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Status Buyer", "desc", "10.00");
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());

        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void buyerListingHidesDiscontinuedProductAndSellerAdminSeesBoth() throws Exception {
        String activeId = createProductAndReturnId(uniqueSku("SKU"), "Produto Ativo Listagem", "desc", "10.00");
        String discontinuedId = createProductAndReturnId(uniqueSku("SKU"), "Produto Descontinuado Listagem", "desc", "10.00");
        String sellerToken = TestJwt.sellerAdminToken();
        mockMvc.perform(put("/products/" + discontinuedId + "/status")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isOk());

        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        MvcResult buyerListing = mockMvc.perform(get("/products?page=0&size=100")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andReturn();
        String buyerBody = buyerListing.getResponse().getContentAsString();
        assertThat(buyerBody).contains(activeId);
        assertThat(buyerBody).doesNotContain(discontinuedId);

        MvcResult sellerListing = mockMvc.perform(get("/products?page=0&size=100")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andReturn();
        String sellerBody = sellerListing.getResponse().getContentAsString();
        assertThat(sellerBody).contains(activeId);
        assertThat(sellerBody).contains(discontinuedId);
    }

    @Test
    void pagedListingWithSizeOneReturnsExactlyOneElementAndTotalGreaterThanOne() throws Exception {
        createProductAndReturnId(uniqueSku("SKU"), "Produto Pagina 1", "desc", "10.00");
        createProductAndReturnId(uniqueSku("SKU"), "Produto Pagina 2", "desc", "10.00");
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(get("/products?page=0&size=1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(1)));
    }

    @Test
    void listingWithSize500ReturnsAtMost100Elements() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(get("/products?size=500")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(100)));
    }

    @Test
    void listingWithoutAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getDiscontinuedProductReturns404ForBuyerAnd200ForSellerAdmin() throws Exception {
        String productId = createProductAndReturnId(uniqueSku("SKU"), "Produto Detalhe Descontinuado", "desc", "10.00");
        String sellerToken = TestJwt.sellerAdminToken();
        mockMvc.perform(put("/products/" + productId + "/status")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISCONTINUED"}
                                """))
                .andExpect(status().isOk());

        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/products/" + productId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());
    }

    @Test
    void creatingProductWithDuplicateSkuReturns409AndDoesNotChangeRowCount() throws Exception {
        String sku = uniqueSku("DUP");
        createProductAndReturnId(sku, "Primeiro Produto Sku", "desc", "10.00");
        long before = productRepository.count();
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(sku, "Segundo Produto Sku", "desc", "20.00")))
                .andExpect(status().isConflict());

        assertThat(productRepository.count()).isEqualTo(before);
    }

    @Test
    void errorBodiesFor400And403And404And409ShareUniformShapeWithoutLeakage() throws Exception {
        String token = TestJwt.sellerAdminToken();

        MvcResult validation = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload("", "Nome", "desc", "-1.00")))
                .andExpect(status().isBadRequest())
                .andReturn();

        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        MvcResult forbidden = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(uniqueSku("SKU"), "Nome", "desc", "1.00")))
                .andExpect(status().isForbidden())
                .andReturn();

        MvcResult notFound = mockMvc.perform(get("/products/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();

        String conflictSku = uniqueSku("CONF");
        createProductAndReturnId(conflictSku, "Produto Conflito Base", "desc", "10.00");
        MvcResult conflict = mockMvc.perform(post("/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createProductPayload(conflictSku, "Produto Conflito Duplicado", "desc", "10.00")))
                .andExpect(status().isConflict())
                .andReturn();

        for (MvcResult result : new MvcResult[]{validation, forbidden, notFound, conflict}) {
            String body = result.getResponse().getContentAsString();
            JsonNode json = objectMapper.readTree(body);
            assertThat(json.has("error")).isTrue();
            assertThat(json.has("message")).isTrue();
            assertThat(body).doesNotContain("Exception");
            assertThat(body).doesNotContain("at com.orderflow");
            assertThat(body).doesNotContain("products_sku_key");
        }
    }
}

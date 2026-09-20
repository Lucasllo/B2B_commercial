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
}

package com.orderflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.inventory.stock.InventoryRepository;
import com.orderflow.inventory.support.TestJwt;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova de INV-01 e INV-02 contra PostgreSQL real. Task 2 (tracer): o vendedor define e redefine
 * o estoque de um produto (upsert, D-18) e qualquer usuario autenticado le a disponibilidade
 * exata (D-25). Task 3 acrescenta reserva e liberacao atomicas/idempotentes.
 */
class InventoryControllerIT extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryRepository inventoryRepository;

    private String setStockPayload(int quantityOnHand) {
        return """
                {"quantityOnHand":%d}
                """.formatted(quantityOnHand);
    }

    // -----------------------------------------------------------------------------------------
    // Task 2 (INV-01, tracer): definir/redefinir estoque real (upsert) e ler a disponibilidade
    // exata de volta.
    // -----------------------------------------------------------------------------------------

    @Test
    void setStockWithSellerAdminTokenCreatesRowAndReturns200WithExpectedBody() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.quantityOnHand").value(50))
                .andExpect(jsonPath("$.quantityReserved").value(0))
                .andExpect(jsonPath("$.quantityAvailable").value(50));
    }

    @Test
    void firstSetStockCallIncreasesRowCountByExactlyOne() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();
        long before = inventoryRepository.count();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(50)))
                .andExpect(status().isOk());

        assertThat(inventoryRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void secondSetStockCallForSameProductUpdatesSameRowInsteadOfCreatingNewOne() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(50)))
                .andExpect(status().isOk());

        long afterFirst = inventoryRepository.count();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(80)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(80));

        assertThat(inventoryRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    void getStockWithSellerAdminTokenReturnsFourFieldsWithAvailableEqualToOnHandMinusReserved() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(50)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.quantityOnHand").value(50))
                .andExpect(jsonPath("$.quantityReserved").value(0))
                .andExpect(jsonPath("$.quantityAvailable").value(50));
    }

    @Test
    void getStockWithBuyerTokenReturns200WithNumericAvailableQuantity() throws Exception {
        String sellerToken = TestJwt.sellerAdminToken();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(50)))
                .andExpect(status().isOk());

        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        MvcResult result = mockMvc.perform(get("/inventory/" + productId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.get("quantityAvailable").isNumber()).isTrue();
    }

    @Test
    void getStockForUnknownProductIdReturns404WithErrorAndMessageKeys() throws Exception {
        String token = TestJwt.sellerAdminToken();
        UUID randomProductId = UUID.randomUUID();

        MvcResult result = mockMvc.perform(get("/inventory/" + randomProductId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.has("error")).isTrue();
        assertThat(json.has("message")).isTrue();
    }

    @Test
    void setStockWithNegativeQuantityReturns400AndEmptyBodyReturns400AndZeroReturns200() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(put("/inventory/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(-1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.quantityOnHand").isNotEmpty());

        mockMvc.perform(put("/inventory/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/inventory/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(0)))
                .andExpect(status().isOk());
    }

    @Test
    void setStockWithBuyerTokenReturns403AndDoesNotCreateRow() throws Exception {
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        UUID productId = UUID.randomUUID();
        long before = inventoryRepository.count();

        mockMvc.perform(put("/inventory/" + productId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(10)))
                .andExpect(status().isForbidden());

        assertThat(inventoryRepository.count()).isEqualTo(before);
    }

    @Test
    void setStockAndGetStockWithoutAuthorizationHeaderReturn401() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(10)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/inventory/" + productId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getStockWithTokenSignedByDifferentKeyReturns401() throws Exception {
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

        mockMvc.perform(get("/inventory/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenSignedByOtherKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getStockWithExpiredTokenReturns401() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build();
        String expiredToken = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, claims);

        mockMvc.perform(get("/inventory/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settingStockForProductIdThatDoesNotExistInAnyCatalogWorksNormally() throws Exception {
        // D-15: productId e referencia opaca — o inventory-service nunca valida contra o
        // catalog-service, entao um UUID aleatorio que nao corresponde a produto nenhum funciona
        // exatamente como qualquer outro.
        String token = TestJwt.sellerAdminToken();
        UUID neverCatalogedProductId = UUID.randomUUID();

        mockMvc.perform(put("/inventory/" + neverCatalogedProductId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setStockPayload(10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(10));
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

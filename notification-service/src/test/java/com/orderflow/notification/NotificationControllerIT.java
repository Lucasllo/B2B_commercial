package com.orderflow.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.notification.support.TestJwt;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3: a consulta do historico vira um contrato fechado — so o vendedor le, token ruim e 401,
 * identificador invalido e 400, e nenhum erro vaza detalhe de implementacao.
 */
class NotificationControllerIT extends AbstractIntegrationTest {

    @Autowired
    private SqsTemplate sqsTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${orderflow.notifications.queue-name}")
    private String queueName;

    private UUID givenProductWithOneEvent() {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(eventId, productId);
        sqsTemplate.send(to -> to.queue(queueName).payload(body));

        String token = TestJwt.sellerAdminToken();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mockMvc.perform(get("/notifications/" + productId)
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1)));
        return productId;
    }

    @Test
    void sellerAdminReadsHistoryOfExistingProduct() throws Exception {
        UUID productId = givenProductWithOneEvent();
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(get("/notifications/" + productId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void buyerTokenReturns403WithoutLeakingEventData() throws Exception {
        UUID productId = givenProductWithOneEvent();
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());

        MvcResult result = mockMvc.perform(get("/notifications/" + productId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.message").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("eventId");
    }

    @Test
    void missingOrInvalidTokenReturns401() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(get("/notifications/" + productId))
                .andExpect(status().isUnauthorized());

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
        mockMvc.perform(get("/notifications/" + productId)
                        .header("Authorization", "Bearer " + tokenSignedByOtherKey))
                .andExpect(status().isUnauthorized());

        JWTClaimsSet expiredClaims = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build();
        String expiredToken = TestJwt.tokenSignedBy(TestJwt.RSA_KEY, expiredClaims);
        mockMvc.perform(get("/notifications/" + productId)
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonUuidIdentifierReturns400WithInvalidIdentifier() throws Exception {
        String token = TestJwt.sellerAdminToken();

        mockMvc.perform(get("/notifications/nao-e-um-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_identifier"));
    }

    @Test
    void errorBodiesNeverLeakExceptionOrStackTraceDetails() throws Exception {
        UUID productId = givenProductWithOneEvent();
        String buyerToken = TestJwt.buyerToken(UUID.randomUUID());
        String token = TestJwt.sellerAdminToken();

        MvcResult forbidden = mockMvc.perform(get("/notifications/" + productId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult badRequest = mockMvc.perform(get("/notifications/nao-e-um-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn();

        for (MvcResult result : new MvcResult[]{forbidden, badRequest}) {
            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(json.has("error")).isTrue();
            assertThat(json.has("message")).isTrue();
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("Exception").doesNotContain("at com.orderflow");
        }
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

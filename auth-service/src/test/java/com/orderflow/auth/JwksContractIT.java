package com.orderflow.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.auth.support.JwksAccessCounter;
import com.orderflow.auth.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova o contrato JWKS (D-03, plano 01-05 Task 2): um decoder construído apenas a partir do JSON
 * publicado em {@code /.well-known/jwks.json}, obtido uma única vez, valida tokens emitidos pelo
 * auth-service sem nenhuma chamada em tempo de execução por token — exatamente como um resource
 * server das Fases 2+ faria.
 */
@Import(JwksAccessCounter.class)
class JwksContractIT extends AbstractIntegrationTest {

    private static final String ISSUER = "orderflow-auth-service";
    private static final String ADMIN_EMAIL = "admin@orderflow.local";
    private static final String ADMIN_PASSWORD = "ChangeMe!123";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AtomicInteger jwksAccessCount;

    /**
     * O bean {@code jwksAccessCount} é um singleton do contexto Spring, compartilhado entre TODOS
     * os métodos {@code @Test} desta classe (contexto único, sem {@code @DirtiesContext} —
     * 01-RESEARCH.md Pitfall 4). Sem este reset, a ordem de execução dos testes JUnit vazaria
     * contagens de um método para o outro e a asserção "exatamente 1" ficaria dependente de
     * ordem. Resetar um {@code AtomicInteger} de teste não reinicia o contexto Spring nem rotaciona
     * o par de chaves RSA — não é {@code @DirtiesContext}.
     */
    @BeforeEach
    void resetJwksAccessCount() {
        jwksAccessCount.set(0);
    }

    private NimbusJwtDecoder buildDecoderFromJwksEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();

        JWKSet jwkSet = JWKSet.parse(result.getResponse().getContentAsString());
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Test
    void decoderBuiltFromJwksEndpointValidatesRealTokenAndExpectedCallCountStaysAtOne() throws Exception {
        NimbusJwtDecoder offlineDecoder = buildDecoderFromJwksEndpoint();
        assertThat(jwksAccessCount.get()).isEqualTo(1);

        String token = TestTokens.loginAndGetToken(mockMvc, objectMapper, ADMIN_EMAIL, ADMIN_PASSWORD);
        Jwt decoded = offlineDecoder.decode(token);

        assertThat(decoded.getSubject()).isNotBlank();
        assertThat(decoded.getClaimAsString("role")).isEqualTo("SELLER_ADMIN");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            offlineDecoder.decode(token);
        }

        // D-03: buscar/cachear a chave pública é permitido; o proibido é uma chamada por token
        // validado. Dez operações depois, o contador continua em 1 — a distinção medida.
        assertThat(jwksAccessCount.get()).isEqualTo(1);
    }

    @Test
    void decoderBuiltFromJwksEndpointRejectsExpiredToken() throws Exception {
        NimbusJwtDecoder offlineDecoder = buildDecoderFromJwksEndpoint();

        // Assinado com a chave real da aplicação (@Autowired do bean RSAKey — mesmo processo/JVM
        // de teste), mas com exp/iat no passado: prova que a rejeição por expiração acontece
        // mesmo com a assinatura correta.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                .build();
        String expiredToken = signWithKey(claims, applicationRsaKey);

        assertThatThrownBy(() -> offlineDecoder.decode(expiredToken)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void decoderBuiltFromJwksEndpointRejectsTokenSignedByDifferentKey() throws Exception {
        NimbusJwtDecoder offlineDecoder = buildDecoderFromJwksEndpoint();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey otherKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("other-key")
                .build();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String tokenSignedByOtherKey = signWithKey(claims, otherKey);

        assertThatThrownBy(() -> offlineDecoder.decode(tokenSignedByOtherKey)).isInstanceOf(RuntimeException.class);
    }

    private static String signWithKey(JWTClaimsSet claims, RSAKey signingKey) throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build();
        SignedJWT signedJWT = new SignedJWT(header, claims);
        signedJWT.sign(new RSASSASigner(signingKey));
        return signedJWT.serialize();
    }

    @Autowired
    private RSAKey applicationRsaKey;
}

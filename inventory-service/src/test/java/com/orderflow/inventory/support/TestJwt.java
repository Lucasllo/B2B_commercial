package com.orderflow.inventory.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Suporte de teste que resolve o problema de o inventory-service nao emitir token: um par de
 * chaves RSA gerado uma unica vez e um {@link JwtDecoder} de teste publicado no contexto, com o
 * mesmo emissor ({@code orderflow-auth-service}) que o auth-service usa. Publicar esse
 * {@code JwtDecoder} faz a autoconfiguracao do resource server recuar (ela so cria o decoder por
 * {@code jwk-set-uri} quando nao existe nenhum bean {@code JwtDecoder}), entao nenhum teste tenta
 * buscar JWKS pela rede — nenhum teste desta fase sobe o auth-service.
 */
public final class TestJwt {

    private static final String ISSUER = "orderflow-auth-service";

    public static final RSAKey RSA_KEY = generateKey();

    private TestJwt() {
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyID("test-key")
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA test key", e);
        }
    }

    public static String sellerAdminToken() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("role", "SELLER_ADMIN")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        return tokenSignedBy(RSA_KEY, claims);
    }

    public static String buyerToken(UUID companyId) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("role", "BUYER")
                .claim("company_id", companyId.toString())
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        return tokenSignedBy(RSA_KEY, claims);
    }

    public static String tokenSignedBy(RSAKey signingKey, JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signingKey.getKeyID())
                    .build();
            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(new RSASSASigner(signingKey));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign test token", e);
        }
    }

    @TestConfiguration
    public static class Config {

        @Bean
        public JwtDecoder jwtDecoder() throws com.nimbusds.jose.JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}

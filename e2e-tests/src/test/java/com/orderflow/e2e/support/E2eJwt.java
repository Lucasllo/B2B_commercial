package com.orderflow.e2e.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Par de chaves RSA gerado UMA ÚNICA VEZ para a JVM de teste inteira — os DOIS contextos (order e
 * inventory) apontam {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} para o MESMO
 * {@code /.well-known/jwks.json} de {@link DownstreamStubServer} ({@link #jwksJson()}), então
 * precisam confiar na mesma chave — ao contrário de {@code TestJwt} (copiado em cada módulo, uma
 * chave por serviço), aqui uma única chave serve os dois resource servers reais.
 *
 * <p>{@code issuer-uri} não é sobrescrito por nenhum override — continua vindo do {@code
 * application.yml} real de cada serviço ({@code orderflow-auth-service}, um literal, não uma URL),
 * então {@link #ISSUER} bate com o que os dois decoders de produção já esperam.
 */
public final class E2eJwt {

    private static final String ISSUER = "orderflow-auth-service";

    private static final RSAKey RSA_KEY = generateKey();

    private E2eJwt() {
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyID("e2e-key")
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key for E2E tests", e);
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
        return sign(claims);
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
        return sign(claims);
    }

    /** Corpo servido por {@code GET /.well-known/jwks.json} no {@link DownstreamStubServer}. */
    public static String jwksJson() {
        return new JWKSet(RSA_KEY.toPublicJWK()).toString();
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(RSA_KEY.getKeyID())
                    .build();
            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(new RSASSASigner(RSA_KEY));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign E2E test token", e);
        }
    }
}

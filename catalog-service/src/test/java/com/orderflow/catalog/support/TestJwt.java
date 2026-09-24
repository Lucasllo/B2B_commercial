package com.orderflow.catalog.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Suporte de teste que resolve o problema de o catalog-service não emitir token: um par de
 * chaves RSA gerado uma única vez, usado para assinar tokens de teste.
 *
 * <p>{@link Config} não publica mais um {@code JwtDecoder} próprio. Em vez disso, publica um
 * {@link JwkSetUriJwtDecoderBuilderCustomizer} que troca só a fonte de chave do decoder
 * autoconfigurado pelo Boot (WR-07): sem bean {@code JwtDecoder} no contexto, o Boot 3.5.16 monta
 * o decoder de PRODUÇÃO a partir do application.yml (jwk-set-uri, RS256, validadores padrão e o
 * validador de emissor do issuer-uri). O customizer roda depois de o Spring montar o seletor de
 * chave remoto (baseado no jwk-set-uri) e o substitui pela chave pública em memória deste
 * arquivo — nenhum teste busca JWKS pela rede, mas o emissor, o algoritmo e os demais validadores
 * continuam vindo da configuração de produção. É isso que faz um token com {@code iss} errado ou
 * ausente ser rejeitado nos testes exatamente como seria em produção (T-03-02/WR-07).
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
        public JwkSetUriJwtDecoderBuilderCustomizer testJwkSourceCustomizer() {
            return builder -> builder.jwtProcessorCustomizer(processor ->
                    processor.setJWSKeySelector(new JWSVerificationKeySelector<SecurityContext>(
                            JWSAlgorithm.RS256,
                            new ImmutableJWKSet<>(new JWKSet(RSA_KEY.toPublicJWK())))));
        }
    }
}

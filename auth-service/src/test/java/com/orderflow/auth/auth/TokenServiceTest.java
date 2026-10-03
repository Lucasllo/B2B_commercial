package com.orderflow.auth.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.orderflow.auth.user.Role;
import com.orderflow.auth.user.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claims do JWT emitido (TEST-01; AUTH-01/AUTH-03): o token é assinado por um {@link NimbusJwtEncoder}
 * real sobre um par RSA gerado no teste e lido de volta por um {@link NimbusJwtDecoder} — prova o
 * token de verdade, não um mock do encoder. Sem Spring context e sem Docker.
 */
class TokenServiceTest {

    private static final long TTL_SECONDS = 1800;

    private static RSAKey rsaKey;
    private static TokenService tokenService;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyID("unit-test-key")
                .algorithm(JWSAlgorithm.RS256)
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        tokenService = new TokenService(encoder, TTL_SECONDS);
        decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    private static User userWithId(UUID id, Role role, UUID companyId) {
        User user = new User("quem@orderflow.local", "hash", role, companyId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** Token de comprador: iss, sub, role, company_id e exp = iat + TTL configurado. */
    @Test
    void buyerTokenCarriesIssuerSubjectRoleCompanyAndTheConfiguredTtl() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokenService.issueToken(userWithId(userId, Role.BUYER, companyId)));

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("orderflow-auth-service");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("BUYER");
        assertThat(jwt.getClaimAsString("company_id")).isEqualTo(companyId.toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds()).isEqualTo(TTL_SECONDS);
        assertThat(tokenService.getTokenTtlSeconds()).isEqualTo(TTL_SECONDS);
    }

    /** Token de vendedor, sem empresa, não leva o claim company_id. */
    @Test
    void sellerTokenWithoutCompanyHasNoCompanyIdClaim() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokenService.issueToken(userWithId(userId, Role.SELLER_ADMIN, null)));

        assertThat(jwt.getClaimAsString("role")).isEqualTo("SELLER_ADMIN");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.hasClaim("company_id")).isFalse();
    }
}

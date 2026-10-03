package com.orderflow.auth.company;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guarda de acesso por empresa sem Spring context (TEST-01; COMP-03): o vendedor vê qualquer
 * empresa, o comprador só a própria, e um principal que não é {@link Jwt} é recusado em vez de
 * estourar. O {@code SecurityContextHolder} é limpo ao final de cada teste.
 */
@ExtendWith(MockitoExtension.class)
class CompanyGuardTest {

    private final CompanyGuard guard = new CompanyGuard();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithJwt(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("sub", UUID.randomUUID().toString());
        Jwt jwt = Jwt.withTokenValue("token-de-teste")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** Sem autenticação no contexto, o acesso é negado. */
    @Test
    void withoutAuthenticationItDeniesAccess() {
        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isFalse();
    }

    /** Principal que não é Jwt devolve false em vez de ClassCastException. */
    @Test
    void aNonJwtPrincipalIsDeniedWithoutThrowing() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isFalse();
    }

    /** SELLER_ADMIN acessa qualquer empresa. */
    @Test
    void sellerAdminCanAccessAnyCompany() {
        authenticateWithJwt(Map.of("role", "SELLER_ADMIN"));

        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isTrue();
        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isTrue();
    }

    /** O comprador acessa a empresa do próprio claim company_id. */
    @Test
    void buyerCanAccessOnlyItsOwnCompany() {
        UUID own = UUID.randomUUID();
        authenticateWithJwt(Map.of("role", "BUYER", "company_id", own.toString()));

        assertThat(guard.isSelfOrSeller(own)).isTrue();
    }

    /** O comprador não acessa a empresa de outro. */
    @Test
    void buyerIsDeniedAnotherCompany() {
        authenticateWithJwt(Map.of("role", "BUYER", "company_id", UUID.randomUUID().toString()));

        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isFalse();
    }

    /** Comprador sem claim company_id não acessa empresa nenhuma. */
    @Test
    void buyerWithoutCompanyClaimIsDenied() {
        authenticateWithJwt(Map.of("role", "BUYER"));

        assertThat(guard.isSelfOrSeller(UUID.randomUUID())).isFalse();
    }
}

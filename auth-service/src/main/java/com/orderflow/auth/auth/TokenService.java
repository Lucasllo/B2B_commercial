package com.orderflow.auth.auth;

import com.orderflow.auth.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Emite o access token do usuário autenticado. Claim {@code company_id} só é incluído quando o
 * usuário pertence a uma empresa — omitido (não nulo) para SELLER_ADMIN (AUTH-02, Flagged
 * Assumptions do plano).
 */
@Service
public class TokenService {

    private static final String ISSUER = "orderflow-auth-service";

    private final JwtEncoder jwtEncoder;
    private final long tokenTtlSeconds;

    public TokenService(JwtEncoder jwtEncoder,
                         @Value("${orderflow.auth.token-ttl-seconds:3600}") long tokenTtlSeconds) {
        this.jwtEncoder = jwtEncoder;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(tokenTtlSeconds))
                .subject(user.getId().toString())
                .claim("role", user.getRole().name());

        if (user.getCompanyId() != null) {
            claimsBuilder.claim("company_id", user.getCompanyId().toString());
        }

        return jwtEncoder.encode(JwtEncoderParameters.from(claimsBuilder.build())).getTokenValue();
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}

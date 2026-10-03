package com.orderflow.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoint público do contrato JWKS consumido pelos serviços das Fases 2+. {@code toPublicJWK()}
 * é o que garante que nenhum material privado (d, p, q, dp, dq, qi) seja publicado (D-02, T-01-05).
 *
 * <p>O endpoint existe para os resource servers validarem o JWT localmente, mas o Gateway não
 * roteia {@code /.well-known/**}. Listá-lo no spec faria o Try it out da Swagger UI do Gateway
 * responder 404, então ele fica {@link Hidden}.
 */
@Hidden
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}

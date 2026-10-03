package com.orderflow.auth.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corpo de resposta de {@code POST /auth/login} bem-sucedido. {@code expiresIn} é o TTL em
 * segundos do access token (D-04 — apenas access token, sem refresh).
 */
@Schema(description = "Access token emitido no login. Não há refresh token.")
public record LoginResponse(
        @Schema(description = "JWT de acesso truncado no exemplo; cole o valor real no Authorize",
                example = "eyJhbGciOi...")
        String accessToken,
        @Schema(description = "Tipo do token", example = "Bearer")
        String tokenType,
        @Schema(description = "TTL do access token em segundos", example = "3600")
        long expiresIn
) {
}

package com.orderflow.auth.auth.dto;

/**
 * Corpo de resposta de {@code POST /auth/login} bem-sucedido. {@code expiresIn} é o TTL em
 * segundos do access token (D-04 — apenas access token, sem refresh).
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}

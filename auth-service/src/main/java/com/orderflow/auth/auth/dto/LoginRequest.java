package com.orderflow.auth.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /auth/login}. Validação de Bean Validation (T-01-07): campos em branco
 * devolvem 400, nunca 401.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}

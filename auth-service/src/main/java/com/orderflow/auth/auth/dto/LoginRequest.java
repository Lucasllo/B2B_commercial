package com.orderflow.auth.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Corpo de {@code POST /auth/login}. Validação de Bean Validation (T-01-07): campos em branco
 * devolvem 400, nunca 401.
 */
@Schema(description = "Credenciais de login. O exemplo usa o usuário de demonstração publicado no README.")
public record LoginRequest(
        @Schema(description = "E-mail do usuário", example = "admin@orderflow.local")
        @NotBlank @Email String email,
        @Schema(description = "Senha do usuário de demonstração", example = "ChangeMe!123")
        @NotBlank String password
) {
}

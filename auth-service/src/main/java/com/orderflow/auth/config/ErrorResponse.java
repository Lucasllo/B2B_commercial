package com.orderflow.auth.config;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Schema só de documentação do corpo de erro. O {@link GlobalExceptionHandler} continua
 * devolvendo {@code Map}; este record não participa do runtime.
 */
@Schema(description = "Corpo de erro uniforme. A chave fields aparece só quando error é validation_failed.")
public record ErrorResponse(
        @Schema(description = "Código estável do erro", example = "validation_failed")
        String error,
        @Schema(description = "Mensagem curta", example = "One or more fields are invalid")
        String message,
        @Schema(description = "Mapa campo para mensagem, presente só em erro de validação",
                example = "{\"email\":\"must not be blank\"}")
        Map<String, String> fields
) {
}

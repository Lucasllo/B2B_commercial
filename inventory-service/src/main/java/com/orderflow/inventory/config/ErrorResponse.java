package com.orderflow.inventory.config;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Schema só de documentação do corpo de erro. O {@link GlobalExceptionHandler} continua
 * devolvendo {@code Map}; este record não participa do runtime.
 */
@Schema(description = "Corpo de erro uniforme. A chave fields aparece só quando error é validation_failed; "
        + "as chaves available e requested aparecem só quando error é insufficient_stock.")
public record ErrorResponse(
        @Schema(description = "Código estável do erro", example = "insufficient_stock")
        String error,
        @Schema(description = "Mensagem curta", example = "Requested quantity exceeds available stock")
        String message,
        @Schema(description = "Mapa campo para mensagem, presente só em erro de validação",
                example = "{\"quantityOnHand\":\"must be greater than or equal to 0\"}")
        Map<String, String> fields,
        @Schema(description = "Quantidade disponível no momento da tentativa, presente só em insufficient_stock",
                example = "5")
        Integer available,
        @Schema(description = "Quantidade pedida, presente só em insufficient_stock", example = "8")
        Integer requested
) {
}

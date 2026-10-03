package com.orderflow.order.config;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schema só de documentação do corpo de erro. O {@link GlobalExceptionHandler} continua
 * devolvendo {@code Map}; este record não participa do runtime.
 */
@Schema(description = "Corpo de erro uniforme. A chave fields aparece só quando error é validation_failed; "
        + "a chave productIds aparece só quando error é invalid_order_items.")
public record ErrorResponse(
        @Schema(description = "Código estável do erro", example = "invalid_order_items")
        String error,
        @Schema(description = "Mensagem curta", example = "One or more items are invalid or unavailable")
        String message,
        @Schema(description = "Mapa campo para mensagem, presente só em erro de validação",
                example = "{\"items\":\"must not be empty\"}")
        Map<String, String> fields,
        @Schema(description = "Produtos que não puderam ser vendidos, presente só em invalid_order_items",
                example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
        List<UUID> productIds
) {
}

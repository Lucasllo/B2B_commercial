package com.orderflow.notification.config;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Schema só de documentação do corpo de erro. O {@link GlobalExceptionHandler} continua
 * devolvendo {@code Map}; este record não participa do runtime. O notification-service não valida
 * corpo de requisição, então não há a chave {@code fields} dos outros serviços.
 */
@Schema(description = "Corpo de erro uniforme: sempre as chaves error e message.")
public record ErrorResponse(
        @Schema(description = "Código estável do erro", example = "order_not_found")
        String error,
        @Schema(description = "Mensagem curta", example = "Order not found")
        String message
) {
}

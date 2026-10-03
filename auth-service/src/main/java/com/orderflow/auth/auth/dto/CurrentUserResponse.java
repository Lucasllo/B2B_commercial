package com.orderflow.auth.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corpo de resposta de {@code GET /auth/me} — extraído diretamente dos claims do JWT já validado
 * localmente, sem nenhuma consulta adicional (AUTH-03, D-03). {@code companyId} é nulo para
 * SELLER_ADMIN (o claim {@code company_id} é omitido do token nesse caso).
 */
@Schema(description = "Claims do JWT já validado. companyId é nulo para SELLER_ADMIN.")
public record CurrentUserResponse(
        @Schema(description = "Identificador do usuário (subject do JWT)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String userId,
        @Schema(description = "Papel do usuário", example = "SELLER_ADMIN")
        String role,
        @Schema(description = "Empresa compradora. Ausente para SELLER_ADMIN.", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        String companyId
) {
}

package com.orderflow.auth.auth.dto;

/**
 * Corpo de resposta de {@code GET /auth/me} — extraído diretamente dos claims do JWT já validado
 * localmente, sem nenhuma consulta adicional (AUTH-03, D-03). {@code companyId} é nulo para
 * SELLER_ADMIN (o claim {@code company_id} é omitido do token nesse caso).
 */
public record CurrentUserResponse(
        String userId,
        String role,
        String companyId
) {
}

package com.orderflow.auth.company.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Corpo de resposta de {@code POST /companies}. Nenhum campo de senha ou de hash aparece aqui —
 * apenas o resumo do usuário BUYER criado (T-01-28).
 */
public record CompanyResponse(
        UUID id,
        String name,
        BigDecimal creditLimit,
        OffsetDateTime createdAt,
        BuyerUserSummary buyerUser
) {

    public record BuyerUserSummary(UUID id, String email, String role) {
    }
}

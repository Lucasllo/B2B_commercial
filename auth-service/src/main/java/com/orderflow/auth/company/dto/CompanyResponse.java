package com.orderflow.auth.company.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Corpo de resposta de {@code POST /companies}. Nenhum campo de senha ou de hash aparece aqui —
 * apenas o resumo do usuário BUYER criado (T-01-28).
 */
@Schema(description = "Empresa compradora criada, com o usuário BUYER vinculado. Não inclui senha.")
public record CompanyResponse(
        @Schema(description = "Identificador da empresa", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        UUID id,
        @Schema(description = "Razão social", example = "Comercial Aurora Ltda")
        String name,
        @Schema(description = "Limite de crédito com duas casas decimais", example = "50000.00")
        BigDecimal creditLimit,
        @Schema(description = "Instante de criação", example = "2026-01-15T12:00:00Z")
        OffsetDateTime createdAt,
        @Schema(description = "Usuário BUYER criado junto com a empresa")
        BuyerUserSummary buyerUser
) {

    @Schema(description = "Resumo do usuário comprador. Sem senha.")
    public record BuyerUserSummary(
            @Schema(description = "Identificador do usuário", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            UUID id,
            @Schema(description = "E-mail do comprador", example = "comprador@exemplo.com")
            String email,
            @Schema(description = "Papel fixo criado pelo servidor", example = "BUYER")
            String role
    ) {
    }
}

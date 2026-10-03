package com.orderflow.auth.company.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo de resposta de {@code GET}/{@code PUT /companies/{companyId}/credit-limit} (plano 01-05,
 * COMP-02).
 */
@Schema(description = "Limite de crédito vigente da empresa compradora.")
public record CreditLimitResponse(
        @Schema(description = "Identificador da empresa", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        UUID companyId,
        @Schema(description = "Limite de crédito com duas casas decimais", example = "50000.00")
        BigDecimal creditLimit
) {
}

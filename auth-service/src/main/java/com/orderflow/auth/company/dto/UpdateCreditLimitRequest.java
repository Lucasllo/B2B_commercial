package com.orderflow.auth.company.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Payload de {@code PUT /companies/{companyId}/credit-limit}. As mesmas restrições de escala
 * monetária de {@link CreateCompanyRequest} (D-06) — o caminho de atualização não é uma porta
 * dos fundos com validação mais frouxa que o caminho de criação (plano 01-05, T-01-35).
 */
@Schema(description = "Novo limite de crédito. Substitui o valor anterior.")
public record UpdateCreditLimitRequest(
        @Schema(description = "Limite de crédito com duas casas decimais", example = "75000.00")
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit
) {
}

package com.orderflow.auth.company.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Corpo de resposta de {@code GET}/{@code PUT /companies/{companyId}/credit-limit} (plano 01-05,
 * COMP-02).
 */
public record CreditLimitResponse(UUID companyId, BigDecimal creditLimit) {
}

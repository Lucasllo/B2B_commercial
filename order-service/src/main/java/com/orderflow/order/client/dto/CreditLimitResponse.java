package com.orderflow.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Espelha por contrato JSON {@code com.orderflow.auth.company.dto.CreditLimitResponse} do
 * auth-service — nunca importado diretamente (ARCHITECTURE.md, anti-padrão de compartilhar
 * classes entre serviços). Só a forma JSON precisa bater; as classes são independentes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditLimitResponse(UUID companyId, BigDecimal creditLimit) {
}

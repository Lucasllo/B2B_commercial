package com.orderflow.auth.user;

/**
 * Papéis suportados pelo OrderFlow nesta fase: SELLER_ADMIN (a empresa vendedora, semeada por
 * Flyway) e BUYER (usuário de uma empresa compradora, criado a partir da Fase 01-04 em diante).
 */
public enum Role {
    BUYER,
    SELLER_ADMIN
}

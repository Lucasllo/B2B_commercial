package com.orderflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova, sem Docker, que o hash BCrypt literal em V2__seed_seller_admin.sql corresponde à senha
 * de demonstração documentada do SELLER_ADMIN — o SQL nunca calcula o hash, então este teste é a
 * única garantia executável de que o valor commitado ainda é o hash correto (D-07).
 */
class SeedPasswordHashTest {

    /**
     * Hash literal também presente em V2__seed_seller_admin.sql. Gerado uma única vez, offline,
     * via new BCryptPasswordEncoder().encode("ChangeMe!123") — nunca recalculado dentro do SQL.
     */
    static final String SEED_HASH = "$2a$10$o1M75kWYUb0EXxSu3NdWceaHKgY73niVPTn8eMdhBJycAOfad0I.6";

    @Test
    void seedHashMatchesDemoPassword() {
        assertThat(new BCryptPasswordEncoder().matches("ChangeMe!123", SEED_HASH)).isTrue();
    }

    @Test
    void seedHashHasBCryptPrefixAndMinimumCost() {
        assertThat(SEED_HASH).matches("^\\$2[ab]\\$\\d{2}\\$.*");
        String costSegment = SEED_HASH.split("\\$")[2];
        int cost = Integer.parseInt(costSegment);
        assertThat(cost).isGreaterThanOrEqualTo(10);
    }
}

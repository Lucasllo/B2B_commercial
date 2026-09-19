package com.orderflow.auth.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mapeia a tabela {@code companies} do schema {@code auth} (V1__init_auth_schema.sql).
 * {@code creditLimit} é sempre {@link BigDecimal} com escala 2 (D-06) — dinheiro nunca como
 * inteiro em centavos, nem {@code double}; a coluna é {@code NUMERIC(19,2)}.
 */
@Entity
@Table(name = "companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    // created_at é gravado pelo default now() do Postgres, nunca pela aplicação;
    // @Generated(INSERT) faz o Hibernate reler o valor gerado pelo banco logo após o INSERT, para
    // que a resposta de criação devolva o timestamp real em vez de null.
    @Generated(GenerationTime.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Company(String name, BigDecimal creditLimit) {
        this.name = name;
        this.creditLimit = creditLimit;
    }

    /**
     * Grava o novo limite de crédito exatamente como recebido — nunca {@code setScale} nem
     * {@code round} aqui; a validação de escala (D-06) já aconteceu no DTO antes deste método ser
     * chamado (plano 01-05, T-01-35).
     */
    public void changeCreditLimit(BigDecimal newCreditLimit) {
        this.creditLimit = newCreditLimit;
    }
}

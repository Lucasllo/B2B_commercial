package com.orderflow.auth.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapeia a tabela {@code users} do schema {@code auth} (V1__init_auth_schema.sql). O SELLER_ADMIN
 * de bootstrap não pertence a nenhuma empresa — companyId fica nulo (D-07).
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "company_id")
    private UUID companyId;

    // insertable/updatable = false: created_at é gravado pelo default now() do Postgres
    // (V1__init_auth_schema.sql), nunca pela aplicação — evita violar o NOT NULL da coluna quando
    // um User é persistido via JPA (o SELLER_ADMIN semeado pela V2 usa INSERT SQL direto e nunca
    // passou por este caminho antes do plano 01-04 criar o primeiro BUYER via CompanyService).
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Construtor usado por {@code CompanyService} para gravar o BUYER vinculado a uma empresa
     * recém-criada. O papel é sempre passado explicitamente pelo chamador (nunca a partir de
     * payload de cliente) — T-01-24.
     */
    public User(String email, String passwordHash, Role role, UUID companyId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.companyId = companyId;
    }
}

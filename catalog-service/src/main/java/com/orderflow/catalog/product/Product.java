package com.orderflow.catalog.product;

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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mapeia a tabela {@code products} do schema {@code catalog} (V1__init_catalog_schema.sql).
 * {@code price} é sempre {@link BigDecimal} com escala 2 (D-06 herdado) — a coluna é
 * {@code NUMERIC(19,2)}. {@code status} é gravado como {@code EnumType.STRING} explícito — nunca
 * o padrão ordinal, que corrompe em silêncio as linhas existentes quando alguém reordena os
 * valores do enum (02-RESEARCH.md Pitfall 4).
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    // created_at é gravado pelo default now() do Postgres, nunca pela aplicação;
    // @Generated(INSERT) faz o Hibernate reler o valor gerado pelo banco logo após o INSERT.
    @Generated(GenerationTime.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * O status nasce sempre {@code ACTIVE}, fixado aqui por literal — o request de criação nunca
     * carrega um campo de status, então não há valor de cliente a ignorar/mapear (mesma
     * mitigação de mass assignment já provada em {@code CompanyService} com {@code Role.BUYER}).
     */
    public Product(String sku, String name, String description, BigDecimal price) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.status = ProductStatus.ACTIVE;
    }

    /**
     * Grava nome, descrição e preço exatamente como recebidos — nunca {@code setScale} nem
     * {@code round} aqui; a validação de escala (D-06) já aconteceu no DTO antes deste método ser
     * chamado (plano 02-01, Task 3). O {@code sku} não tem método de mutação: é imutável depois
     * da criação, porque é o código pelo qual o vendedor identifica o item fora do sistema.
     */
    public void updateDetails(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    /**
     * Único caminho de retirada/reativação do catálogo (D-23). Nenhum método deste tipo nem do
     * repositório invoca operação de remoção de linha — os pedidos das Fases 4 a 6 referenciam
     * produtos por id, e um apagamento físico destruiria o histórico.
     */
    public void changeStatus(ProductStatus newStatus) {
        this.status = newStatus;
    }
}

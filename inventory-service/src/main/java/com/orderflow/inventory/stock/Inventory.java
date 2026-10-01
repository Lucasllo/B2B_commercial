package com.orderflow.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.GenerationTime;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mapeia a tabela {@code inventory} do schema {@code inventory} (V1__init_inventory_schema.sql).
 * A coluna {@code version} ({@link Version}) e o mecanismo de lock otimista do JPA: cada
 * {@code UPDATE} bem-sucedido a incrementa e a compara na clausula {@code WHERE}, de modo que duas
 * atualizacoes concorrentes produzem uma vencedora e uma que lanca
 * {@code ObjectOptimisticLockingFailureException} — e essa excecao que
 * {@code InventoryService.reserve}/{@code release} reexecuta automaticamente ({@code RetryConfig},
 * D-20). {@code productId} e referencia opaca ao catalogo (D-15) — nenhuma FK cruza a fronteira
 * entre os dois servicos.
 */
@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    @Version
    @Column(nullable = false)
    private long version;

    // created_at e gravado pelo default now() do Postgres, nunca pela aplicacao;
    // @Generated(INSERT) faz o Hibernate reler o valor gerado pelo banco logo apos o INSERT.
    @Generated(GenerationTime.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Inventory(UUID productId, int quantityOnHand) {
        this.productId = productId;
        this.quantityOnHand = quantityOnHand;
        this.quantityReserved = 0;
    }

    /**
     * Redefine a quantidade em estoque (upsert, D-18). Quem decide se a nova quantidade e menor
     * do que o ja reservado — e portanto se a chamada deve ser recusada em vez de aplicada — e
     * {@code InventoryService.setStock}, nao esta entidade.
     */
    public void setOnHand(int novaQuantidade) {
        this.quantityOnHand = novaQuantidade;
    }

    /**
     * Incrementa a quantidade reservada. A checagem de disponibilidade (D-10) acontece em
     * {@code InventoryService} antes de chamar este metodo — a entidade nao valida sozinha porque
     * a garantia real contra sobrevenda e a coluna {@code version} mais a constraint
     * {@code chk_inventory_not_oversold} do banco, nao uma checagem em Java.
     */
    public void reserve(int quantidade) {
        this.quantityReserved += quantidade;
    }

    /**
     * Decrementa a quantidade reservada sem descer abaixo de zero (liberacao idempotente, D-14).
     */
    public void release(int quantidade) {
        this.quantityReserved = Math.max(0, this.quantityReserved - quantidade);
    }

    /**
     * Baixa fisica na expedicao (D-75, D-57): decrementa {@code quantityOnHand} e {@code
     * quantityReserved} pelo MESMO valor, entao o disponivel nao muda. Diferente de {@link
     * #release}, nunca mascara com {@code Math.max}: quantidade invalida, maior que o reservado ou
     * maior que o fisico e anomalia tecnica ({@link IllegalStateException}) — o livro e o inventario
     * estao inconsistentes e isso nao pode ser silenciado.
     */
    public void ship(int quantidade) {
        if (quantidade < 1 || quantidade > this.quantityReserved || quantidade > this.quantityOnHand) {
            throw new IllegalStateException("Baixa invalida de " + quantidade + " unidade(s) para productId="
                    + this.productId + ": onHand=" + this.quantityOnHand + ", reserved=" + this.quantityReserved);
        }
        this.quantityOnHand -= quantidade;
        this.quantityReserved -= quantidade;
    }

    public int availableQuantity() {
        return this.quantityOnHand - this.quantityReserved;
    }
}

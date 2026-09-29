package com.orderflow.inventory.stock;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mapeia a tabela {@code stock_reservations} — o livro de idempotencia de reserva/liberacao
 * (D-11, D-14). A garantia real de idempotencia e a constraint
 * {@code uq_stock_reservations_product_reservation UNIQUE (product_id, reservation_id)} no banco
 * (RESERVATION_ID_SCOPE=scope-per-product, Task 1 deste plano); a checagem antecipada em
 * {@code InventoryService} e so o caminho rapido do caso sem corrida.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "reservation_id", nullable = false, length = 255)
    private String reservationId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean released;

    @Generated(GenerationTime.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    public StockReservation(UUID productId, String reservationId, int quantity) {
        this.productId = productId;
        this.reservationId = reservationId;
        this.quantity = quantity;
        this.released = false;
    }

    /**
     * Fábrica de LÁPIDE (D-66) — um {@code ReleaseStock} chegando ANTES do {@code ReserveStock}
     * correspondente (fila SQS padrão, sem ordem garantida) grava a linha já {@code released =
     * true}, inclusive para produto SEM linha de estoque cadastrada (D-58, {@code
     * TOMBSTONE_FK=dropped} — a FK para {@code inventory(product_id)} foi removida em V3
     * exatamente para permitir isto). Um {@code ReserveStock} posterior com o mesmo {@code
     * reservationId} encontra esta linha e responde falha ({@code RESERVATION_CANCELLED}, {@link
     * InventoryService#reserveAll}) em vez de reservar — resolve a corrida da fila padrão sem
     * precisar de FIFO.
     */
    public static StockReservation tombstone(UUID productId, String reservationId, int quantity, OffsetDateTime now) {
        StockReservation reservation = new StockReservation(productId, reservationId, quantity);
        reservation.markReleased(now);
        return reservation;
    }

    /**
     * Marca a reserva como liberada. Consumido uma unica vez: liberar de novo ou reservar de novo
     * com o mesmo identificador nao reaproveita esta linha para um novo incremento (D-11 vale
     * inclusive apos a liberacao).
     */
    public void markReleased(OffsetDateTime quando) {
        this.released = true;
        this.releasedAt = quando;
    }
}

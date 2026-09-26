package com.orderflow.order.order;

import com.orderflow.order.order.exception.OrderNotPendingException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entidade da tabela {@code orders} ({@code V1__init_order_schema.sql}, {@code
 * V2__order_reservation_saga.sql}). Nasce sempre {@code CREATED} (fábrica {@link #create}) e
 * transiciona para {@code APPROVED} ou {@code PENDING_APPROVAL} na mesma transação que a cria
 * (D-45) — {@code CREATED} é o estado de origem do domínio, nunca observado pela API desta fase.
 * A partir da Fase 5 (D-50), {@code APPROVED} é apenas um passo lógico da decisão: quem chama
 * {@link #approveAutomatically}/{@code approveManually} chama {@link #startReservation} em
 * seguida, na mesma transação — nenhum pedido novo repousa persistido em {@code APPROVED}; o
 * status observável é sempre {@code RESERVING}. Transição a partir de um estado que não satisfaz a
 * guarda do método é erro de programação — {@link IllegalStateException}, nunca resposta ao
 * cliente.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    /** {@code decidedBy} quando a decisão é automática (D-45) — nunca um UUID/FK (04-RESEARCH.md Pitfall 3). */
    public static final String SYSTEM_DECIDER = "SYSTEM";
    public static final String AUTO_APPROVAL_REASON = "dentro do limite de crédito";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(length = 500)
    private String reason;

    @Column(name = "reservation_started_at")
    private OffsetDateTime reservationStartedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Nasce {@code CREATED}, cria um {@link OrderItem} por {@link PricedItem} (preservando a
     * ordem/numeração de linha já atribuída) e calcula {@code total} como a soma dos subtotais.
     */
    public static Order create(UUID companyId, String createdBy, List<PricedItem> pricedItems, OffsetDateTime now) {
        Order order = new Order();
        order.companyId = companyId;
        order.createdBy = createdBy;
        order.createdAt = now;
        order.status = OrderStatus.CREATED;

        BigDecimal total = BigDecimal.ZERO;
        for (PricedItem pricedItem : pricedItems) {
            order.items.add(new OrderItem(order, pricedItem));
            total = total.add(pricedItem.subtotal());
        }
        order.total = total;
        return order;
    }

    /**
     * Decisão automática (D-45): grava {@code decidedBy}/{@code decidedAt}/{@code reason} como
     * antes da Fase 5. A transição lógica para {@code APPROVED} nunca é observada pela API — quem
     * chama esta transição em seguida chama {@link #startReservation} na mesma transação (D-50),
     * então o status persistido de um pedido novo dentro do limite é sempre {@code RESERVING}.
     */
    public void approveAutomatically(OffsetDateTime now) {
        requireCreated();
        this.status = OrderStatus.APPROVED;
        this.decidedBy = SYSTEM_DECIDER;
        this.decidedAt = now;
        this.reason = AUTO_APPROVAL_REASON;
    }

    public void holdForApproval() {
        requireCreated();
        this.status = OrderStatus.PENDING_APPROVAL;
    }

    /**
     * Aprovação manual do vendedor (ORD-03, D-46) — só a partir de {@code PENDING_APPROVAL};
     * qualquer outro estado lança {@link OrderNotPendingException} sem tocar em nenhum campo, para
     * não sobrescrever uma decisão já registrada. Ao contrário de {@link #approveAutomatically},
     * não reavalia o limite de crédito nem é chamada pela criação — o vendedor assume o risco de
     * estourar o limite (D-38); a exposição da empresa passa a contar este pedido porque {@code
     * APPROVED} está em {@link OrderStatus#CREDIT_CONSUMING}. Motivo em branco é gravado como nulo.
     */
    public void approveManually(String decidedBy, String reason, OffsetDateTime now) {
        requirePendingApproval();
        this.status = OrderStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.reason = blankToNull(reason);
    }

    /**
     * Rejeição do vendedor (ORD-03, D-37, D-46) — mesma guarda de {@link #approveManually}: só a
     * partir de {@code PENDING_APPROVAL}, senão {@link OrderNotPendingException} sem tocar em
     * nenhum campo. Motivo é obrigatório (nulo ou em branco lança {@link
     * IllegalArgumentException}) — o agregado se defende mesmo que a validação do DTO falhe.
     * {@code REJECTED} não está em {@link OrderStatus#CREDIT_CONSUMING}: um pedido rejeitado não
     * consome crédito (D-37).
     */
    public void reject(String decidedBy, String reason, OffsetDateTime now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank when rejecting an order");
        }
        requirePendingApproval();
        this.status = OrderStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.reason = reason;
    }

    /**
     * Ponto de entrada único da saga (D-48, chamado só por {@link
     * com.orderflow.order.saga.ReservationSagaStarter#start}) — exige {@code APPROVED} (o passo
     * lógico da decisão, D-50); qualquer outro estado é erro de programação, nunca resposta ao
     * cliente ({@link IllegalStateException}, mesmo estilo de {@link #requireCreated}), porque
     * {@link com.orderflow.order.saga.ReservationSagaStarter} é sempre chamado logo depois de
     * {@link #approveAutomatically}/{@code approveManually} dentro da mesma transação.
     */
    public void startReservation(OffsetDateTime now) {
        requireApproved();
        this.status = OrderStatus.RESERVING;
        this.reservationStartedAt = now;
    }

    private void requireCreated() {
        if (this.status != OrderStatus.CREATED) {
            throw new IllegalStateException("Order " + id + " is not CREATED (status=" + status + ")");
        }
    }

    private void requireApproved() {
        if (this.status != OrderStatus.APPROVED) {
            throw new IllegalStateException("Order " + id + " is not APPROVED (status=" + status + ")");
        }
    }

    private void requirePendingApproval() {
        if (this.status != OrderStatus.PENDING_APPROVAL) {
            throw new OrderNotPendingException();
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}

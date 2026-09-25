package com.orderflow.order.order;

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
 * Entidade da tabela {@code orders} ({@code V1__init_order_schema.sql}). Nasce sempre {@code
 * CREATED} (fábrica {@link #create}) e transiciona para {@code APPROVED} ou {@code
 * PENDING_APPROVAL} na mesma transação que a cria (D-45) — {@code CREATED} é o estado de origem
 * do domínio, nunca observado pela API desta fase. Transição a partir de outro estado que não
 * {@code CREATED} é erro de programação — {@link IllegalStateException}, nunca resposta ao
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

    private void requireCreated() {
        if (this.status != OrderStatus.CREATED) {
            throw new IllegalStateException("Order " + id + " is not CREATED (status=" + status + ")");
        }
    }
}

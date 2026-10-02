package com.orderflow.order.order;

import com.orderflow.order.order.exception.InvalidOrderTransitionException;
import com.orderflow.order.order.exception.OrderNotPendingException;
import com.orderflow.order.shipping.CarrierAssignment;
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

    /** Correlation-ID da requisição que criou o pedido (D-95). Nulo em pedidos legados. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(length = 500)
    private String reason;

    @Column(name = "reservation_started_at")
    private OffsetDateTime reservationStartedAt;

    /** Código de cancelamento (D-53) — nulo até {@link #cancel}, coluna do CHECK da V2. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_code", length = 40)
    private CancellationCode cancellationCode;

    /** Texto legível montado por {@code CancellationReasons} — nunca texto livre da mensagem (D-56). */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    /** Transportadora simulada (D-70) — atribuída uma única vez, junto de {@code confirmedAt}. */
    @Column(name = "carrier", length = 64)
    private String carrier;

    /** Código de rastreio S10 simulado (D-73) — atribuído junto da transportadora, nunca trocado. */
    @Column(name = "tracking_code", length = 13)
    private String trackingCode;

    /** Expedição (D-76) — preenchidos pelo endpoint de /ship (06-02); nulos até lá. */
    @Column(name = "shipped_at")
    private OffsetDateTime shippedAt;

    @Column(name = "shipped_by", length = 64)
    private String shippedBy;

    /** Entrega (D-76) — preenchidos pelo endpoint de /deliver (06-02); nulos até lá. */
    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "delivered_by", length = 64)
    private String deliveredBy;

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
     * Grava o ID de criação uma única vez (D-95). Chamadas seguintes são ignoradas — o ID da
     * requisição que criou o pedido não é substituído por transições posteriores.
     */
    public void recordCorrelationId(String correlationId) {
        if (this.correlationId == null) {
            this.correlationId = correlationId;
        }
    }

    /**
     * Decisão automática (D-45): grava {@code decidedBy}/{@code decidedAt}/{@code reason} como
     * antes da Fase 5. A transição lógica para {@code APPROVED} nunca é observada pela API — quem
     * chama esta transição em seguida chama {@link #startReservation} na mesma transação (D-50),
     * então o status persistido de um pedido novo dentro do limite é sempre {@code RESERVING}.
     */
    public void approveAutomatically(OffsetDateTime now) {
        if (this.status != OrderStatus.CREATED) {
            throw notIn(OrderStatus.CREATED);
        }
        moveTo(OrderStatus.APPROVED, () -> notIn(OrderStatus.CREATED));
        this.decidedBy = SYSTEM_DECIDER;
        this.decidedAt = now;
        this.reason = AUTO_APPROVAL_REASON;
    }

    public void holdForApproval() {
        moveTo(OrderStatus.PENDING_APPROVAL, () -> notIn(OrderStatus.CREATED));
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
        if (this.status != OrderStatus.PENDING_APPROVAL) {
            throw new OrderNotPendingException();
        }
        moveTo(OrderStatus.APPROVED, OrderNotPendingException::new);
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
        moveTo(OrderStatus.REJECTED, OrderNotPendingException::new);
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.reason = reason;
    }

    /**
     * Ponto de entrada único da saga (D-48, chamado só por {@link
     * com.orderflow.order.saga.ReservationSagaStarter#start}) — exige {@code APPROVED} (o passo
     * lógico da decisão, D-50); qualquer outro estado é erro de programação, nunca resposta ao
     * cliente ({@link IllegalStateException}, mesmo estilo de {@link #holdForApproval}), porque
     * {@link com.orderflow.order.saga.ReservationSagaStarter} é sempre chamado logo depois de
     * {@link #approveAutomatically}/{@code approveManually} dentro da mesma transação.
     */
    public void startReservation(OffsetDateTime now) {
        moveTo(OrderStatus.RESERVING, () -> notIn(OrderStatus.APPROVED));
        this.reservationStartedAt = now;
    }

    /**
     * Aplica o resultado de FALHA da reserva (ORD-05, D-53, D-64) — chamado só por {@link
     * com.orderflow.order.saga.OrderSagaService#applyReservationFailed}, sob a trava de linha do
     * pedido ({@code SAGA_RESULT_LOCK}), depois de já ter confirmado {@code status == RESERVING}.
     * Guarda por estado aqui também, para que um erro de programação (chamada a partir de outro
     * estado) nunca vire uma resposta ao cliente: {@code decidedBy}/{@code decidedAt}/{@code
     * reason} nunca são tocados — pertencem à decisão do vendedor/sistema, não ao resultado da
     * saga (D-53).
     */
    public void cancel(CancellationCode code, String reason, OffsetDateTime now) {
        moveTo(OrderStatus.CANCELLED, () -> notIn(OrderStatus.RESERVING));
        this.cancellationCode = code;
        this.cancellationReason = reason;
        this.cancelledAt = now;
    }

    /**
     * Aplica o resultado de SUCESSO da reserva (ORD-05, D-57) — chamado só por {@link
     * com.orderflow.order.saga.OrderSagaService#applyStockReserved} a partir de {@code RESERVING}.
     * O estoque continua reservado no inventory-service; a baixa física de {@code quantity_on_hand}
     * é do {@code ShipStock} da expedição (D-75) — nada aqui chama o inventory-service.
     *
     * <p>D-70: nunca existe {@code CONFIRMED} sem transportadora — a atribuição é obrigatória e
     * gravada junto de {@code confirmedAt}, na mesma transação. Atribuição nula lança {@link
     * IllegalArgumentException} antes de qualquer mudança; status diferente de {@code RESERVING}
     * lança {@link IllegalStateException}.
     */
    public void confirm(OffsetDateTime now, CarrierAssignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("a confirmed order requires a carrier assignment");
        }
        moveTo(OrderStatus.CONFIRMED, () -> notIn(OrderStatus.RESERVING));
        this.confirmedAt = now;
        this.carrier = assignment.carrier();
        this.trackingCode = assignment.trackingCode();
    }

    /**
     * Expedição pelo vendedor (ORD-10, D-74) — só a partir de {@code CONFIRMED}; qualquer outro
     * estado lança {@link InvalidOrderTransitionException} sem tocar em nenhum campo (D-77).
     * {@code shippedBy} é o claim {@code sub} do JWT e é obrigatório: nulo ou em branco lança {@link
     * IllegalArgumentException} antes de qualquer mudança. Transportadora, rastreio e {@code
     * confirmedAt} não mudam (D-76).
     *
     * <p>D-75: a baixa física do estoque é assíncrona — o pedido vai direto a {@code SHIPPED}, sem
     * estado intermediário, e o comando {@code ShipStock} é gravado no outbox por quem chama, na
     * mesma transação. {@code SHIPPED} continua em {@link OrderStatus#CREDIT_CONSUMING} (D-37).
     */
    public void ship(String shippedBy, OffsetDateTime now) {
        if (shippedBy == null || shippedBy.isBlank()) {
            throw new IllegalArgumentException("shippedBy must not be blank when shipping an order");
        }
        moveTo(OrderStatus.SHIPPED, () -> new InvalidOrderTransitionException(status, OrderStatus.SHIPPED));
        this.shippedAt = now;
        this.shippedBy = shippedBy;
    }

    /**
     * Entrega registrada pelo vendedor (ORD-10, D-74) — só a partir de {@code SHIPPED}; qualquer
     * outro estado lança {@link InvalidOrderTransitionException} sem tocar em nenhum campo (D-77).
     * Mesmo molde de {@link #ship}: {@code deliveredBy} (claim {@code sub}) é obrigatório e os campos
     * de expedição não mudam. {@code DELIVERED} é terminal e segue em {@link
     * OrderStatus#CREDIT_CONSUMING} (D-37) — nenhum endpoint desta fase libera o crédito.
     */
    public void deliver(String deliveredBy, OffsetDateTime now) {
        if (deliveredBy == null || deliveredBy.isBlank()) {
            throw new IllegalArgumentException("deliveredBy must not be blank when delivering an order");
        }
        moveTo(OrderStatus.DELIVERED, () -> new InvalidOrderTransitionException(status, OrderStatus.DELIVERED));
        this.deliveredAt = now;
        this.deliveredBy = deliveredBy;
    }

    /**
     * ÚNICO ponto da classe que atribui {@code this.status} (D-83, ORD-10): consulta a tabela única
     * {@link OrderStatus#canTransitionTo} e, se a aresta não existe, lança a exceção fornecida ANTES
     * de tocar em qualquer campo — assim uma transição recusada nunca deixa o agregado pela metade.
     * (A fábrica {@link #create} atribui o status inicial ao objeto recém-criado.)
     *
     * <p>Guarda de gatilho nas duas aprovações: {@code APPROVED} tem duas origens na tabela ({@code
     * CREATED}, pela aprovação automática, e {@code PENDING_APPROVAL}, pela manual), e cada método
     * representa UMA aresta — então {@link #approveAutomatically} só vale a partir de {@code
     * CREATED} e {@link #approveManually} só a partir de {@code PENDING_APPROVAL}, verificado antes
     * deste método. Toda outra aresta tem origem única na tabela, e a tabela basta como guarda.
     */
    private void moveTo(OrderStatus target, java.util.function.Supplier<? extends RuntimeException> rejection) {
        if (!this.status.canTransitionTo(target)) {
            throw rejection.get();
        }
        this.status = target;
    }

    private IllegalStateException notIn(OrderStatus expected) {
        return new IllegalStateException("Order " + id + " is not " + expected + " (status=" + status + ")");
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}

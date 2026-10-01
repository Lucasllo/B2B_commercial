package com.orderflow.order.saga;

import com.orderflow.order.order.CancellationCode;
import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderItem;
import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import com.orderflow.order.saga.messaging.InvalidSagaMessageException;
import com.orderflow.order.saga.messaging.dto.ReleaseStockCommand;
import com.orderflow.order.saga.messaging.dto.ReservationLine;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.order.saga.messaging.dto.StockReservedEvent;
import com.orderflow.order.saga.outbox.OutboxWriter;
import com.orderflow.order.shipping.CarrierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Aplica o resultado da reserva de estoque ao pedido, guardado pelo ESTADO — nunca por uma tabela
 * de mensagens processadas (D-64). {@code SAGA_RESULT_LOCK=order-row}: trava a linha do PEDIDO
 * ({@link OrderRepository#findByIdForUpdate}, {@code PESSIMISTIC_WRITE}), não a {@code
 * company_credit_lock} — liberar crédito ao cancelar não arrisca estourar limite (Claude's
 * Discretion, 05-CONTEXT.md), mas o resultado da reserva e o job de timeout (05-04) disputam o
 * MESMO pedido: sem uma trava comum, um {@code StockReserved} tardio poderia sobrescrever um
 * {@code CANCELLED} já compensado. A trava de linha serializa só as transições da saga, sem
 * bloquear criação de pedidos nem decisões da empresa (que travam {@code company_credit_lock}).
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;
    private final CarrierGateway carrierGateway;

    public OrderSagaService(OrderRepository orderRepository, OutboxWriter outboxWriter,
                            CarrierGateway carrierGateway) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
        this.carrierGateway = carrierGateway;
    }

    /**
     * {@code StockReservationFailed} → {@code CANCELLED} (ORD-05), só a partir de {@code
     * RESERVING}. Pedido ausente é consumido e registrado em log (não há o que fazer com ele, e
     * reentregar só o levaria à DLQ). Status diferente de RESERVING é duplicata/resultado tardio
     * — ignorado sem alterar nada (D-64), inclusive se já CANCELLED por um resultado anterior.
     */
    @Transactional
    public void applyReservationFailed(StockReservationFailedEvent event) {
        Optional<Order> maybeOrder = orderRepository.findByIdForUpdate(event.orderId());
        if (maybeOrder.isEmpty()) {
            log.warn("Resultado da saga recebido para pedido inexistente orderId={}", event.orderId());
            return;
        }
        Order order = maybeOrder.get();
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            log.info("Resultado de falha ignorado (duplicata ou tardio) orderId={} status={}",
                    order.getId(), order.getStatus());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String reason = CancellationReasons.forFailure(event.reasonCode(), event.failures(), order);
        order.cancel(CancellationCode.valueOf(event.reasonCode()), reason, now);
    }

    /**
     * {@code StockReserved} → {@code CONFIRMED} (ORD-05, D-57), só a partir de {@code RESERVING}.
     * O estoque continua reservado no inventory-service — nada aqui chama o inventory-service (a
     * baixa física de {@code quantity_on_hand} é o {@code ShipStock} da expedição, D-75). Na mesma
     * transação que grava o {@code CONFIRMED}, a {@link CarrierGateway} simulada atribui
     * transportadora e código de rastreio (ORD-07, D-70).
     *
     * <p>{@code STOCK_RESERVED_ITEMS_CHECK}: se os itens do evento diferirem dos itens do pedido
     * (produto ou quantidade), a mensagem é tratada como INVÁLIDA — {@link
     * InvalidSagaMessageException} propaga para o listener descartar com log, e a transação não
     * grava nada (a fila é uma fronteira de confiança; um sucesso forjado não confirma pedido).
     *
     * <p>Um {@code StockReserved} tardio para um pedido já {@code CANCELLED} NUNCA é ignorado em
     * silêncio (D-64): grava, na MESMA transação que leu o pedido cancelado, um {@code
     * ReleaseStock} no outbox com {@code reason=LATE_RESERVATION} — devolve o estoque em vez de
     * deixá-lo órfão. A liberação é idempotente no inventory-service (lápide e livro, 05-04), então
     * emitir um {@code ReleaseStock} a cada entrega duplicada de um sucesso tardio é seguro.
     * Qualquer outro status (ex.: já {@code CONFIRMED}) é duplicata — ignorada sem alterar nada.
     */
    @Transactional
    public void applyStockReserved(StockReservedEvent event) {
        Optional<Order> maybeOrder = orderRepository.findByIdForUpdate(event.orderId());
        if (maybeOrder.isEmpty()) {
            log.warn("Resultado da saga recebido para pedido inexistente orderId={}", event.orderId());
            return;
        }
        Order order = maybeOrder.get();
        if (!itemsMatchOrder(event.items(), order)) {
            throw new InvalidSagaMessageException(
                    "Campo items do StockReserved nao bate com os itens do pedido orderId=" + order.getId());
        }

        if (order.getStatus().canTransitionTo(OrderStatus.CONFIRMED)) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            // D-70: transportadora e rastreio entram na MESMA transação e sob a MESMA trava de
            // linha do CONFIRMED. O gateway é determinístico, sem I/O e sem exceção (D-72); só este
            // ramo o chama, então duplicata e sucesso tardio nunca reatribuem.
            order.confirm(now, carrierGateway.assign(order.getId()));
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            UUID eventId = UUID.randomUUID();
            ReleaseStockCommand command = ReleaseStockCommand.from(
                    order, eventId, now.toInstant(), ReleaseStockCommand.LATE_RESERVATION);
            outboxWriter.enqueue(command.eventId(), ReleaseStockCommand.EVENT_TYPE, order.getId().toString(), command);
            log.info("StockReserved tardio para pedido ja CANCELLED orderId={} - ReleaseStock gravado no outbox",
                    order.getId());
            return;
        }
        log.info("Resultado de sucesso ignorado (duplicata) orderId={} status={}", order.getId(), order.getStatus());
    }

    /**
     * Garantia de CÓDIGO do "nunca preso" (D-63, Goal do ROADMAP) — chamado só por {@link
     * com.orderflow.order.saga.SagaTimeoutJob#run}, uma vez por pedido vencido, cada um na sua
     * PRÓPRIA transação. Trava a linha do pedido (mesma trava de {@link #applyReservationFailed}/
     * {@link #applyStockReserved}, {@code SAGA_RESULT_LOCK=order-row}) e reavalia status e prazo
     * SOB a trava — o resultado da reserva pode ter chegado entre a consulta do job (fora de
     * transação) e esta transação; se o pedido não estiver mais em {@code RESERVING} ou já não
     * satisfizer mais o {@code cutoff}, é no-op. Pedido ausente também é no-op.
     *
     * <p>Cancela com {@link CancellationCode#RESERVATION_TIMEOUT} e grava, na MESMA transação, um
     * {@code ReleaseStock} no outbox — compensa uma reserva que possa ter acontecido tarde (se não
     * aconteceu, vira lápide no inventory-service, D-66, 05-04 Task 2).
     */
    @Transactional
    public void expireReservation(UUID orderId, OffsetDateTime cutoff) {
        Optional<Order> maybeOrder = orderRepository.findByIdForUpdate(orderId);
        if (maybeOrder.isEmpty()) {
            return;
        }
        Order order = maybeOrder.get();
        if (!order.getStatus().canTransitionTo(OrderStatus.CANCELLED)
                || order.getReservationStartedAt() == null
                || !order.getReservationStartedAt().isBefore(cutoff)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        order.cancel(CancellationCode.RESERVATION_TIMEOUT, CancellationReasons.forTimeout(), now);

        UUID eventId = UUID.randomUUID();
        ReleaseStockCommand command = ReleaseStockCommand.from(
                order, eventId, now.toInstant(), ReleaseStockCommand.RESERVATION_TIMEOUT);
        outboxWriter.enqueue(command.eventId(), ReleaseStockCommand.EVENT_TYPE, order.getId().toString(), command);
    }

    /** Compara por (productId → quantity), independente de ordem — {@code STOCK_RESERVED_ITEMS_CHECK}. */
    private boolean itemsMatchOrder(List<ReservationLine> eventItems, Order order) {
        Map<UUID, Integer> expected = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        Map<UUID, Integer> actual = eventItems.stream()
                .collect(Collectors.toMap(ReservationLine::productId, ReservationLine::quantity));
        return expected.equals(actual);
    }
}

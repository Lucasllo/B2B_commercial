package com.orderflow.order.saga;

import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Garantia de CÓDIGO do "nunca preso" (D-63, Goal do ROADMAP) — não depende de retry do SQS nem
 * de o resultado da reserva algum dia chegar. Conta a partir de {@code reservation_started_at}
 * ({@code SAGA_TIMEOUT_CLOCK}, V2), nunca de {@code decided_at}: um pedido legado migrado pela V2
 * tem {@code decided_at} antigo e seria cancelado no primeiro ciclo se o timeout contasse a partir
 * dele.
 *
 * <p>Bean SEPARADO de {@link OrderSagaService} de propósito, mesma razão de {@code
 * OutboxRelayJob}/{@code OutboxRelay}: se {@code run()} chamasse {@code expireReservation}
 * diretamente no mesmo bean, a chamada pularia o proxy transacional do Spring (auto-invocação —
 * 02-RESEARCH.md Pitfall 2). Cada pedido vencido é processado na SUA PRÓPRIA transação, pelo proxy
 * de {@link OrderSagaService}: uma exceção ao processar um pedido é registrada em log ERROR com o
 * id e o laço segue para os demais — o timeout de um pedido nunca pode ficar refém de um erro
 * noutro (mesmo espírito de {@code OutboxRelay.publishPendingBatch}, T-05-02).
 */
@Component
public class SagaTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);

    private final OrderRepository orderRepository;
    private final OrderSagaService orderSagaService;
    private final Duration reservationTimeout;
    private final int batchSize;

    public SagaTimeoutJob(OrderRepository orderRepository, OrderSagaService orderSagaService,
                           @Value("${orderflow.saga.reservation-timeout}") Duration reservationTimeout,
                           @Value("${orderflow.saga.timeout-batch-size}") int batchSize) {
        this.orderRepository = orderRepository;
        this.orderSagaService = orderSagaService;
        this.reservationTimeout = reservationTimeout;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${orderflow.saga.timeout-check-interval}")
    public void run() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(reservationTimeout);
        Pageable page = PageRequest.of(0, batchSize);
        List<UUID> expiredOrderIds = orderRepository.findExpiredReservationIds(OrderStatus.RESERVING, cutoff, page);
        for (UUID orderId : expiredOrderIds) {
            try {
                orderSagaService.expireReservation(orderId, cutoff);
            } catch (RuntimeException e) {
                log.error("Falha ao expirar reserva do pedido orderId={} - job segue para os demais", orderId, e);
            }
        }
    }
}

package com.orderflow.order.saga;

import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitário (Mockito, sem Spring, T-05-02) de {@link SagaTimeoutJob} — {@code run()} consulta o
 * repositório pelo {@code cutoff}/página corretos e chama {@link
 * OrderSagaService#expireReservation} para cada id devolvido, cada um isoladamente: uma exceção ao
 * processar o primeiro pedido não impede o segundo de ser processado.
 */
@ExtendWith(MockitoExtension.class)
class SagaTimeoutJobTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final int BATCH_SIZE = 50;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderSagaService orderSagaService;

    @Test
    void runQueriesRepositoryWithReservingStatusCutoffNearNowMinusTimeoutAndConfiguredBatchSizeThenCallsExpireReservation() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findExpiredReservationIds(eq(OrderStatus.RESERVING), any(), any()))
                .thenReturn(List.of(orderId));

        SagaTimeoutJob job = new SagaTimeoutJob(orderRepository, orderSagaService, TIMEOUT, BATCH_SIZE);
        job.run();

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findExpiredReservationIds(
                eq(OrderStatus.RESERVING), cutoffCaptor.capture(), pageableCaptor.capture());

        OffsetDateTime expectedCutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(TIMEOUT);
        assertThat(Duration.between(cutoffCaptor.getValue(), expectedCutoff).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(BATCH_SIZE);
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();

        verify(orderSagaService).expireReservation(orderId, cutoffCaptor.getValue());
    }

    @Test
    void oneFailingOrderDoesNotPreventTheSecondFromBeingProcessed() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(orderRepository.findExpiredReservationIds(eq(OrderStatus.RESERVING), any(), any()))
                .thenReturn(List.of(first, second));
        doThrow(new RuntimeException("simulated failure"))
                .when(orderSagaService).expireReservation(eq(first), any());

        SagaTimeoutJob job = new SagaTimeoutJob(orderRepository, orderSagaService, TIMEOUT, BATCH_SIZE);
        job.run();

        verify(orderSagaService).expireReservation(eq(first), any());
        verify(orderSagaService).expireReservation(eq(second), any());
    }
}

package com.orderflow.order.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Trava a linha do pedido (D-64, {@code SAGA_RESULT_LOCK=order-row}) — usada por {@code
     * OrderSagaService} para serializar as transições da saga (resultado da reserva e, em
     * 05-04, o job de timeout) sobre o MESMO pedido, sem depender de {@code
     * company_credit_lock}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Soma de exposição (D-37): fonte única de verdade nos próprios pedidos, nunca um saldo
     * desnormalizado. Devolve {@code null} quando não há nenhuma linha para a empresa nos status
     * informados — quem chama converte em zero.
     */
    @Query("SELECT SUM(o.total) FROM Order o WHERE o.companyId = :companyId AND o.status IN :statuses")
    BigDecimal sumTotalByCompanyIdAndStatusIn(@Param("companyId") UUID companyId,
                                               @Param("statuses") Collection<OrderStatus> statuses);

    /** Visão do BUYER, sem filtro por status (D-47) — o escopo por empresa vem só daqui, nunca de um parâmetro de query. */
    Page<Order> findByCompanyId(UUID companyId, Pageable pageable);

    /** Visão do BUYER, filtrada pela fila de aprovação ou qualquer outro status (D-47, 04-RESEARCH.md Open Question 2). */
    Page<Order> findByCompanyIdAndStatus(UUID companyId, OrderStatus status, Pageable pageable);

    /** Visão do SELLER_ADMIN filtrada por status — sem filtro nenhum, o vendedor usa o {@code findAll} herdado. */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * Ids de pedidos presos em {@code status} há mais tempo que {@code cutoff} (D-63, garantia de
     * código do "nunca preso") — usa o índice {@code idx_orders_status_reservation_started_at}
     * (V2, {@code SAGA_TIMEOUT_CLOCK}). Ordenado do mais antigo para o mais novo, para que {@code
     * SagaTimeoutJob} processe primeiro quem está preso há mais tempo. Sem trava aqui — a consulta
     * roda fora de transação; {@code OrderSagaService#expireReservation} reavalia status/prazo sob
     * {@link #findByIdForUpdate} antes de qualquer transição.
     */
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.reservationStartedAt < :cutoff "
            + "ORDER BY o.reservationStartedAt ASC")
    List<UUID> findExpiredReservationIds(@Param("status") OrderStatus status,
                                          @Param("cutoff") OffsetDateTime cutoff,
                                          Pageable pageable);
}

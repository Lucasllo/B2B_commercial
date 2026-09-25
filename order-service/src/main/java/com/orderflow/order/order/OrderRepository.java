package com.orderflow.order.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

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
}

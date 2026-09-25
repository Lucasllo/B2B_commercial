package com.orderflow.order.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.credit.CreditPolicy;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.OrderNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Único bean deste pacote com métodos {@code @Transactional}. Não depende de nenhum cliente HTTP
 * (D-41) — recebe itens já precificados e o limite de crédito já lido por {@link
 * OrderCreationService}, nunca chama {@code RestClient} daqui.
 */
@Service
public class OrderService {

    private final CompanyCreditLocker creditLocker;
    private final OrderRepository orderRepository;

    public OrderService(CompanyCreditLocker creditLocker, OrderRepository orderRepository) {
        this.creditLocker = creditLocker;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse createWithCreditCheck(UUID companyId, String createdBy, List<PricedItem> pricedItems,
                                                BigDecimal creditLimit) {
        // A trava é adquirida ANTES de qualquer leitura de exposição — serializa a checagem por
        // empresa (D-40); empresas diferentes não se bloqueiam entre si.
        creditLocker.acquire(companyId);

        // Tomado depois da trava: sob concorrência, decidedAt respeita a ordem real de
        // serialização. Truncado para microssegundos — a precisão do TIMESTAMPTZ do Postgres;
        // sem o truncamento, a resposta do POST (montada em memória) e a do GET (relida do banco)
        // divergiriam no último dígito de nanossegundo.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        Order order = Order.create(companyId, createdBy, pricedItems, now);

        BigDecimal exposure = orderRepository.sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING);
        if (exposure == null) {
            exposure = BigDecimal.ZERO;
        }

        if (CreditPolicy.fitsWithinLimit(exposure, order.getTotal(), creditLimit)) {
            order.approveAutomatically(now);
        } else {
            order.holdForApproval();
        }

        order = orderRepository.save(order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(UUID orderId, UUID callerCompanyId, boolean sellerView) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        // Um BUYER de outra empresa recebe o mesmo 404 de um pedido inexistente — nunca revela
        // que o pedido existe (D-47).
        if (!sellerView && !order.getCompanyId().equals(callerCompanyId)) {
            throw new OrderNotFoundException("Order not found");
        }
        return OrderResponse.from(order);
    }
}

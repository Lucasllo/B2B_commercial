package com.orderflow.order.order;

import com.orderflow.order.order.dto.OrderResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /orders/{id}/ship} e {@code POST /orders/{id}/deliver} (ORD-10, D-74) — só
 * SELLER_ADMIN (T-06-05). Controller irmão de {@link OrderDecisionController}: cada ação de ciclo de
 * vida numa classe pequena. Sem corpo — transportadora e rastreio já foram atribuídos ao confirmar
 * (D-76). Quem agiu sai sempre do claim {@code sub} do próprio JWT, nunca do corpo (T-06-09).
 */
@RestController
@RequestMapping("/orders")
public class OrderShipmentController {

    private final OrderShipmentService orderShipmentService;

    public OrderShipmentController(OrderShipmentService orderShipmentService) {
        this.orderShipmentService = orderShipmentService;
    }

    @PostMapping("/{orderId}/ship")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public OrderResponse ship(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderShipmentService.ship(orderId, requireSellerId(jwt));
    }

    @PostMapping("/{orderId}/deliver")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public OrderResponse deliver(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderShipmentService.deliver(orderId, requireSellerId(jwt));
    }

    /** Lê o claim {@code sub} do JWT; ausente ou em branco lança {@link AccessDeniedException} (403). */
    private String requireSellerId(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new AccessDeniedException("sub claim is missing");
        }
        return sub;
    }
}

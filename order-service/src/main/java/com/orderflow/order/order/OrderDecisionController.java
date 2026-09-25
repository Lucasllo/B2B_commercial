package com.orderflow.order.order;

import com.orderflow.order.order.dto.ApproveOrderRequest;
import com.orderflow.order.order.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code POST /orders/{id}/approve} e {@code POST /orders/{id}/reject} (ORD-03, D-46) — só
 * SELLER_ADMIN decide (T-04-20). Segundo controller sobre o mesmo caminho base {@code /orders},
 * para não disputar {@link OrderController} com o plano {@code 04-03}. O decisor sai sempre do
 * claim {@code sub} do próprio JWT, nunca de um campo do corpo (T-04-21) — sem {@code Location}
 * na resposta, é transição de estado, não criação.
 */
@RestController
@RequestMapping("/orders")
public class OrderDecisionController {

    private final OrderDecisionService orderDecisionService;

    public OrderDecisionController(OrderDecisionService orderDecisionService) {
        this.orderDecisionService = orderDecisionService;
    }

    @PostMapping("/{orderId}/approve")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public OrderResponse approve(@PathVariable UUID orderId,
                                  @Valid @RequestBody(required = false) ApproveOrderRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        String sellerId = requireSellerId(jwt);
        String reason = request == null ? null : request.reason();
        return orderDecisionService.approve(orderId, sellerId, reason);
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

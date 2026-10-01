package com.orderflow.notification.history;

import com.orderflow.notification.history.dto.NotificationResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Historico por entidade — sempre lista por Query, nunca item unico (D-31).
 *
 * <p>{@code GET /notifications/{productId}} fica restrito a SELLER_ADMIN (Claude's Discretion de
 * {@code 03-CONTEXT.md}): o historico de estoque e dado operacional do vendedor sobre o proprio
 * catalogo, e negar por padrao e mais barato de relaxar depois do que de apertar.
 *
 * <p>{@code GET /notifications/orders/{orderId}} (D-81) e a "regra nova e explicita" prevista na
 * Fase 3: SELLER_ADMIN le a linha do tempo de qualquer pedido; BUYER le somente a do pedido da
 * propria empresa ({@code company_id} do JWT), e recebe o mesmo 404 para pedido de outra empresa,
 * inexistente ou sem eventos. A decisao de acesso fica em
 * {@link NotificationService#historyForOrder}; aqui so se deriva o papel e a empresa do JWT.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('SELLER_ADMIN','BUYER')")
    public List<NotificationResponse> getOrderTimeline(@PathVariable UUID orderId, Authentication authentication) {
        boolean sellerView = isSellerAdmin(authentication);
        UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
        return notificationService.historyForOrder(orderId, callerCompanyId, sellerView);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public List<NotificationResponse> getHistory(@PathVariable UUID productId) {
        return notificationService.history(productId);
    }

    /**
     * Le o claim {@code company_id} do JWT; ausente, vazio ou nao-UUID lanca
     * {@link AccessDeniedException} (403) — um BUYER sem empresa nao tem escopo. A empresa nunca
     * vem do caminho nem de parametro (mesmo molde de {@code OrderController#requireCompanyId}).
     */
    private UUID requireCompanyId(Jwt jwt) {
        String companyIdClaim = jwt.getClaimAsString("company_id");
        if (companyIdClaim == null || companyIdClaim.isBlank()) {
            throw new AccessDeniedException("company_id claim is missing");
        }
        try {
            return UUID.fromString(companyIdClaim);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("company_id claim is not a valid UUID");
        }
    }

    private boolean isSellerAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SELLER_ADMIN_AUTHORITY::equals);
    }
}

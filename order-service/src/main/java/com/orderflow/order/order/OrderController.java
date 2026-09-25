package com.orderflow.order.order;

import com.orderflow.order.order.dto.CreateOrderRequest;
import com.orderflow.order.order.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * {@code POST /orders} — só o papel BUYER cria pedidos (ORD-01). {@code GET /{orderId}} — aberto
 * a qualquer autenticado; a diferença de escopo (só a própria empresa vs. qualquer pedido) é
 * resolvida em {@link OrderService#getById}, nunca aqui (Pattern 3, 04-RESEARCH.md).
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    private final OrderCreationService orderCreationService;
    private final OrderService orderService;

    public OrderController(OrderCreationService orderCreationService, OrderService orderService) {
        this.orderCreationService = orderCreationService;
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasRole('BUYER')")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID companyId = requireCompanyId(jwt);
        String createdBy = jwt.getSubject();
        String bearerToken = jwt.getTokenValue();

        OrderResponse response = orderCreationService.create(companyId, createdBy, bearerToken, request);
        return ResponseEntity.created(URI.create("/orders/" + response.id())).body(response);
    }

    @GetMapping("/{orderId}")
    public OrderResponse getById(@PathVariable UUID orderId, Authentication authentication) {
        boolean sellerView = isSellerAdmin(authentication);
        UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
        return orderService.getById(orderId, callerCompanyId, sellerView);
    }

    /**
     * Lê o claim {@code company_id} do JWT; ausente, vazio ou não-UUID lança {@link
     * AccessDeniedException} (403) — um BUYER sem empresa não tem escopo. O {@code companyId}
     * nunca vem do corpo da requisição (Claude's Discretion resolvida: só do JWT).
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

    /**
     * Deriva o indicador de visão de vendedor a partir da {@code Authentication} do contexto,
     * checando a authority {@code ROLE_SELLER_ADMIN} — nunca do corpo nem de um parâmetro de
     * query (mesmo padrão de {@code ProductController}).
     */
    private boolean isSellerAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SELLER_ADMIN_AUTHORITY::equals);
    }
}

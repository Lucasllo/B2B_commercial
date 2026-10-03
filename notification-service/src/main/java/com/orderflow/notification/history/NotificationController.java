package com.orderflow.notification.history;

import com.orderflow.notification.config.ErrorResponse;
import com.orderflow.notification.history.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Histórico de notificações", description = "Histórico de eventos consumidos da fila: ajustes de estoque por produto e linha do tempo por pedido, em ordem cronológica.")
public class NotificationController {

    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "Linha do tempo do pedido",
            description = "SELLER_ADMIN lê a linha do tempo de qualquer pedido; BUYER só a do pedido da própria empresa. "
                    + "Pedido de outra empresa, inexistente ou sem eventos devolve o mesmo 404 (order_not_found), "
                    + "sem revelar se o pedido existe.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Eventos ORDER_* do pedido em ordem cronológica",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = NotificationResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador não é UUID (invalid_identifier)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel sem acesso ou comprador sem company_id válido (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido não encontrado para quem consultou (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "DynamoDB indisponível (notification_store_unavailable)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PreAuthorize("hasAnyRole('SELLER_ADMIN','BUYER')")
    public List<NotificationResponse> getOrderTimeline(
            @Parameter(description = "Identificador do pedido (UUID)", example = "3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b")
            @PathVariable UUID orderId, Authentication authentication) {
        boolean sellerView = isSellerAdmin(authentication);
        UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
        return notificationService.historyForOrder(orderId, callerCompanyId, sellerView);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Histórico de ajustes de estoque do produto",
            description = "Exige o papel SELLER_ADMIN: o histórico de estoque é dado operacional do vendedor. "
                    + "Lista vazia quando o produto não tem eventos registrados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Eventos do produto em ordem cronológica",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = NotificationResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Identificador não é UUID (invalid_identifier)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "DynamoDB indisponível (notification_store_unavailable)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public List<NotificationResponse> getHistory(
            @Parameter(description = "Identificador do produto (UUID)", example = "7d9e4a20-1b3c-4f5d-8e6a-2b4c6d8e0f12")
            @PathVariable UUID productId) {
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

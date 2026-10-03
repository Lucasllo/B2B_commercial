package com.orderflow.order.order;

import com.orderflow.order.config.ErrorResponse;
import com.orderflow.order.order.dto.ApproveOrderRequest;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.dto.RejectOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Decisão do vendedor", description = "Aprovação e rejeição manual de pedidos em PENDING_APPROVAL. Exige o papel SELLER_ADMIN.")
public class OrderDecisionController {

    private final OrderDecisionService orderDecisionService;

    public OrderDecisionController(OrderDecisionService orderDecisionService) {
        this.orderDecisionService = orderDecisionService;
    }

    @PostMapping("/{orderId}/approve")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Aprovar pedido",
            description = "Exige o papel SELLER_ADMIN. Só vale para pedido em PENDING_APPROVAL; a aprovação registra o decisor e o motivo opcional e o pedido entra em RESERVING, iniciando a reserva de estoque pela saga. O corpo é opcional.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pedido aprovado (já em RESERVING)",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "orderId que não é UUID (invalid_parameter), motivo longo demais (validation_failed) ou corpo malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido inexistente (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Pedido fora de PENDING_APPROVAL (order_not_pending)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse approve(
            @Parameter(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) ApproveOrderRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        String sellerId = requireSellerId(jwt);
        String reason = request == null ? null : request.reason();
        return orderDecisionService.approve(orderId, sellerId, reason);
    }

    @PostMapping("/{orderId}/reject")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Rejeitar pedido",
            description = "Exige o papel SELLER_ADMIN. Só vale para pedido em PENDING_APPROVAL; o motivo é obrigatório. O pedido vai a REJECTED, estado terminal que libera o crédito.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pedido rejeitado (REJECTED)",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "orderId que não é UUID (invalid_parameter), motivo ausente ou em branco (validation_failed) ou corpo ausente/malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido inexistente (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Pedido fora de PENDING_APPROVAL (order_not_pending)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse reject(
            @Parameter(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID orderId,
            @Valid @RequestBody RejectOrderRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        String sellerId = requireSellerId(jwt);
        return orderDecisionService.reject(orderId, sellerId, request.reason());
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

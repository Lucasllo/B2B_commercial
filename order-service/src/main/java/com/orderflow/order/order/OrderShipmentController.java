package com.orderflow.order.order;

import com.orderflow.order.config.ErrorResponse;
import com.orderflow.order.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Expedição e entrega", description = "Ciclo de vida pós-confirmação: expedir e registrar a entrega. Exige o papel SELLER_ADMIN; não há corpo, a transportadora e o rastreio já foram atribuídos ao confirmar.")
public class OrderShipmentController {

    private final OrderShipmentService orderShipmentService;

    public OrderShipmentController(OrderShipmentService orderShipmentService) {
        this.orderShipmentService = orderShipmentService;
    }

    @PostMapping("/{orderId}/ship")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Expedir pedido",
            description = "Exige o papel SELLER_ADMIN. Só vale para pedido CONFIRMED; o pedido vai a SHIPPED e registra quem expediu e quando. Sem corpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pedido expedido (SHIPPED)",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "orderId que não é UUID (invalid_parameter)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido inexistente (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Pedido fora de CONFIRMED (invalid_order_transition)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse ship(
            @Parameter(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderShipmentService.ship(orderId, requireSellerId(jwt));
    }

    @PostMapping("/{orderId}/deliver")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Registrar entrega do pedido",
            description = "Exige o papel SELLER_ADMIN. Só vale para pedido SHIPPED; o pedido vai a DELIVERED, estado terminal. Sem corpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Entrega registrada (DELIVERED)",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "orderId que não é UUID (invalid_parameter)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido inexistente (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Pedido fora de SHIPPED (invalid_order_transition)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse deliver(
            @Parameter(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
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

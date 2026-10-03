package com.orderflow.order.order;

import com.orderflow.order.config.ErrorResponse;
import com.orderflow.order.order.dto.CreateOrderRequest;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.dto.OrderSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestParam;
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
@Tag(name = "Pedidos", description = "Criação e consulta de pedidos. Só o papel BUYER cria; qualquer usuário autenticado consulta, com escopo pela própria empresa (BUYER) ou visão total (SELLER_ADMIN).")
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
    @Operation(summary = "Criar pedido",
            description = "Exige o papel BUYER; a empresa vem do claim company_id do JWT, nunca do corpo. Preços e total são calculados no servidor a partir do catálogo. Dentro do limite de crédito o pedido é aprovado automaticamente e já entra em RESERVING (reserva de estoque pela saga); acima do limite nasce PENDING_APPROVAL e espera a decisão do vendedor.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pedido criado (RESERVING ou PENDING_APPROVAL); o header Location aponta para GET /orders/{orderId}",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed, inclusive produto repetido) ou malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de BUYER ou claim company_id ausente (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Item inexistente ou indisponível (invalid_order_items, com productIds) ou total fora da faixa permitida (order_total_out_of_range)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Catálogo (catalog_service_unavailable) ou auth-service (auth_service_unavailable) indisponível; nenhum pedido é criado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID companyId = requireCompanyId(jwt);
        String createdBy = jwt.getSubject();
        String bearerToken = jwt.getTokenValue();

        OrderResponse response = orderCreationService.create(companyId, createdBy, bearerToken, request);
        return ResponseEntity.created(URI.create("/orders/" + response.id())).body(response);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Buscar pedido por id",
            description = "Exige usuário autenticado. O BUYER só enxerga pedidos da própria empresa (pedido de outra empresa responde 404, sem revelar que existe); o SELLER_ADMIN enxerga qualquer pedido.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pedido encontrado",
                    content = @Content(schema = @Schema(implementation = OrderResponse.class))),
            @ApiResponse(responseCode = "400", description = "orderId que não é UUID (invalid_parameter)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "BUYER sem claim company_id válido (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pedido inexistente ou de outra empresa (order_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public OrderResponse getById(
            @Parameter(description = "Identificador do pedido", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID orderId, Authentication authentication) {
        boolean sellerView = isSellerAdmin(authentication);
        UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
        return orderService.getById(orderId, callerCompanyId, sellerView);
    }

    /**
     * {@code GET /orders} (ORD-08/ORD-09) — aberto a qualquer autenticado; nenhum parâmetro escolhe
     * empresa. O escopo do BUYER vem de {@link #requireCompanyId}, a visão de vendedor vem de
     * {@link #isSellerAdmin} — o mesmo par de derivações de {@link #getById}. {@code status} vale
     * para os dois papéis (D-47, 04-RESEARCH.md Open Question 2).
     */
    @GetMapping
    @Operation(summary = "Listar pedidos",
            description = "Exige usuário autenticado. O BUYER lista só os pedidos da própria empresa; o SELLER_ADMIN lista os de todas. O filtro status vale para os dois papéis.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Página de resumos de pedido"),
            @ApiResponse(responseCode = "400", description = "status ou parâmetro de paginação inválido (invalid_parameter)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "BUYER sem claim company_id válido (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Parameter(name = "page", in = ParameterIn.QUERY, description = "Número da página, começando em 0",
            schema = @Schema(type = "integer", minimum = "0", example = "0"))
    @Parameter(name = "size", in = ParameterIn.QUERY, description = "Quantidade de itens na página",
            schema = @Schema(type = "integer", minimum = "1", example = "20"))
    @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Ordenação no formato propriedade,direção",
            schema = @Schema(type = "string", example = "createdAt,desc"))
    public Page<OrderSummaryResponse> list(
            @Parameter(description = "Filtra pelo status do pedido",
                    schema = @Schema(implementation = OrderStatus.class))
            @RequestParam(required = false) OrderStatus status,
            @Parameter(hidden = true) Pageable pageable, Authentication authentication) {
        boolean sellerView = isSellerAdmin(authentication);
        UUID callerCompanyId = sellerView ? null : requireCompanyId((Jwt) authentication.getPrincipal());
        return orderService.list(callerCompanyId, sellerView, status, pageable);
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

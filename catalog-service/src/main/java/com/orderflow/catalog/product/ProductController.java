package com.orderflow.catalog.product;

import com.orderflow.catalog.config.ErrorResponse;
import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import com.orderflow.catalog.product.dto.UpdateProductRequest;
import com.orderflow.catalog.product.dto.UpdateProductStatusRequest;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * {@code POST /products} — só o papel SELLER_ADMIN cria produtos (CAT-01). {@code PUT /{id}} e
 * {@code PUT /{id}/status} — só SELLER_ADMIN atualiza e retira/reativa (D-23). {@code GET}
 * (coleção e detalhe) — qualquer autenticado chama, sem {@code @PreAuthorize} de papel; a
 * diferença de conteúdo vem do filtro por status no serviço (CAT-02, D-24). Nenhum bean de guard
 * por objeto no estilo do {@code CompanyGuard} da Fase 1 é necessário aqui: o catálogo é único e
 * visível a todo comprador autenticado por desenho (D-16, D-24).
 */
@RestController
@RequestMapping("/products")
@Tag(name = "Produtos", description = "Catálogo do vendedor. Leitura exige usuário autenticado; criação, atualização e mudança de status exigem SELLER_ADMIN.")
public class ProductController {

    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Criar produto", description = "Exige o papel SELLER_ADMIN. O status nasce ACTIVE no servidor.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Produto criado",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed) ou malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "SKU já utilizado (sku_already_used)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = productService.create(request);
        return ResponseEntity.created(URI.create("/products/" + response.id())).body(response);
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Buscar produto por id",
            description = "Exige usuário autenticado. O comprador não recebe produto DISCONTINUED; o SELLER_ADMIN vê qualquer status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Produto encontrado",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto inexistente ou invisível para o comprador (product_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ProductResponse getById(
            @Parameter(description = "Identificador do produto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            Authentication authentication) {
        return productService.getById(productId, isSellerAdmin(authentication));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Atualizar produto", description = "Exige o papel SELLER_ADMIN. Não altera SKU nem status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Produto atualizado",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed) ou malformado (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto inexistente (product_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "SKU já utilizado (sku_already_used)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ProductResponse update(
            @Parameter(description = "Identificador do produto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(productId, request);
    }

    @PutMapping("/{productId}/status")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Alterar status do produto",
            description = "Exige o papel SELLER_ADMIN. Único caminho de retirada (DISCONTINUED) ou reativação (ACTIVE).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status alterado",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed) ou status desconhecido (malformed_request)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Produto inexistente (product_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ProductResponse changeStatus(
            @Parameter(description = "Identificador do produto", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductStatusRequest request) {
        return productService.changeStatus(productId, request.status());
    }

    @GetMapping
    @Operation(summary = "Listar produtos",
            description = "Exige usuário autenticado. O comprador recebe só produtos ACTIVE; o SELLER_ADMIN recebe todos.")
    @ApiResponses({
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Parameter(name = "page", in = ParameterIn.QUERY, description = "Número da página, começando em 0",
            schema = @Schema(type = "integer", minimum = "0", example = "0"))
    @Parameter(name = "size", in = ParameterIn.QUERY, description = "Quantidade de itens na página",
            schema = @Schema(type = "integer", minimum = "1", example = "20"))
    @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Ordenação no formato propriedade,direção",
            schema = @Schema(type = "string", example = "name,asc"))
    public Page<ProductResponse> list(@Parameter(hidden = true) Pageable pageable, Authentication authentication) {
        return productService.list(pageable, isSellerAdmin(authentication));
    }

    /**
     * Deriva o indicador de visão de vendedor a partir da {@code Authentication} do contexto,
     * checando a authority {@code ROLE_SELLER_ADMIN} — nunca do corpo nem de um parâmetro de
     * query, porque isso permitiria a um comprador pedir a visão completa.
     */
    private boolean isSellerAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SELLER_ADMIN_AUTHORITY::equals);
    }
}

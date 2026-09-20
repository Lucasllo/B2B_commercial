package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import com.orderflow.catalog.product.dto.UpdateProductRequest;
import com.orderflow.catalog.product.dto.UpdateProductStatusRequest;
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
public class ProductController {

    private static final String SELLER_ADMIN_AUTHORITY = "ROLE_SELLER_ADMIN";

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse response = productService.create(request);
        return ResponseEntity.created(URI.create("/products/" + response.id())).body(response);
    }

    @GetMapping("/{productId}")
    public ProductResponse getById(@PathVariable UUID productId, Authentication authentication) {
        return productService.getById(productId, isSellerAdmin(authentication));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ProductResponse update(@PathVariable UUID productId, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(productId, request);
    }

    @PutMapping("/{productId}/status")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ProductResponse changeStatus(@PathVariable UUID productId,
                                         @Valid @RequestBody UpdateProductStatusRequest request) {
        return productService.changeStatus(productId, request.status());
    }

    @GetMapping
    public Page<ProductResponse> list(Pageable pageable, Authentication authentication) {
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

package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * {@code POST /products} — só o papel SELLER_ADMIN cria produtos (CAT-01). {@code GET /{id}} —
 * qualquer autenticado consulta um produto por id (Task 3 acrescenta a regra de visibilidade por
 * papel para produtos descontinuados, atualização e retirada por status).
 */
@RestController
@RequestMapping("/products")
public class ProductController {

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
    public ProductResponse getById(@PathVariable UUID productId) {
        return productService.getById(productId);
    }
}

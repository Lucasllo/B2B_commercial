package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Regras de catálogo: criação com status fixado no servidor e consulta por id (Task 2). Task 3
 * acrescenta atualização, retirada por status e listagem paginada filtrada por papel.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        // Status nunca vem do cliente — fixado por literal no construtor de Product (mesma
        // mitigação de mass assignment já provada em CompanyService com Role.BUYER).
        Product product = productRepository.save(new Product(
                request.sku(), request.name(), request.description(), request.price()));
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
        return ProductResponse.from(product);
    }
}

package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import com.orderflow.catalog.product.dto.UpdateProductRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Regras de catálogo: criação com status fixado no servidor, atualização, retirada/reativação por
 * status e listagem filtrada por papel (CAT-01, CAT-02, D-23, D-24).
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        // Checagem antecipada, defensiva: a UNIQUE constraint em products.sku é a garantia real,
        // mas checar antes evita gravar o produto em cenários sem condição de corrida (mesma
        // filosofia já comentada em CompanyService.createCompanyWithBuyer).
        if (productRepository.existsBySku(request.sku())) {
            throw new SkuAlreadyUsedException("SKU already in use");
        }

        // Status nunca vem do cliente — fixado por literal no construtor de Product (mesma
        // mitigação de mass assignment já provada em CompanyService com Role.BUYER).
        Product product = productRepository.save(new Product(
                request.sku(), request.name(), request.description(), request.price()));
        return ProductResponse.from(product);
    }

    /**
     * @param sellerView quando falso e o produto encontrado está {@code DISCONTINUED}, o produto
     *                   é tratado como inexistente para o comprador — um produto fora do catálogo
     *                   é indistinguível de inexistente (D-24).
     */
    @Transactional(readOnly = true)
    public ProductResponse getById(UUID productId, boolean sellerView) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
        if (!sellerView && product.getStatus() == ProductStatus.DISCONTINUED) {
            throw new ProductNotFoundException("Product not found");
        }
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
        product.updateDetails(request.name(), request.description(), request.price());
        return ProductResponse.from(product);
    }

    /**
     * Único caminho de retirada do catálogo — nenhum método deste serviço nem do repositório
     * invoca operação de remoção de linha, porque os pedidos das Fases 4 a 6 referenciam produtos
     * por id e um apagamento físico destruiria o histórico.
     */
    @Transactional
    public ProductResponse changeStatus(UUID productId, ProductStatus newStatus) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
        product.changeStatus(newStatus);
        return ProductResponse.from(product);
    }

    /**
     * @param sellerView quando verdadeiro devolve todos os produtos (ativos e descontinuados);
     *                   caso contrário filtra apenas {@code ACTIVE} (D-24).
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable, boolean sellerView) {
        Page<Product> page = sellerView
                ? productRepository.findAll(pageable)
                : productRepository.findByStatus(ProductStatus.ACTIVE, pageable);
        return page.map(ProductResponse::from);
    }
}

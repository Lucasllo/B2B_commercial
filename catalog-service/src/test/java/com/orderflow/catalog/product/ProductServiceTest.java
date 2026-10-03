package com.orderflow.catalog.product;

import com.orderflow.catalog.product.dto.CreateProductRequest;
import com.orderflow.catalog.product.dto.ProductResponse;
import com.orderflow.catalog.product.dto.UpdateProductRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regras centrais do catálogo sem Spring nem Docker (TEST-01, D-23/D-24): SKU único, status
 * fixado em ACTIVE na criação, produto DISCONTINUED invisível ao comprador e listagem do comprador
 * filtrada por ACTIVE. Cada teste afirma a regra por captor, estado ou exceção.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService service() {
        return new ProductService(productRepository);
    }

    private static Product product(String sku, ProductStatus status) {
        Product product = new Product(sku, "Nome " + sku, "desc", new BigDecimal("10.00"));
        product.changeStatus(status);
        return product;
    }

    /** SKU duplicado é recusado antes de qualquer gravação (D-23). */
    @Test
    void createWithExistingSkuThrowsSkuAlreadyUsedAndNeverSaves() {
        when(productRepository.existsBySku("DUP-1")).thenReturn(true);

        assertThatThrownBy(() -> service().create(
                new CreateProductRequest("DUP-1", "Nome", "desc", new BigDecimal("5.00"))))
                .isInstanceOf(SkuAlreadyUsedException.class);

        verify(productRepository, never()).save(any(Product.class));
    }

    /** O status nasce ACTIVE no servidor e a resposta devolve os campos do request. */
    @Test
    void createWithFreeSkuSavesAnActiveProductAndReturnsTheRequestFields() {
        when(productRepository.existsBySku("NEW-1")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service().create(
                new CreateProductRequest("NEW-1", "Cafe", "Pacote 1kg", new BigDecimal("29.90")));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(captor.getValue().getSku()).isEqualTo("NEW-1");
        assertThat(response.sku()).isEqualTo("NEW-1");
        assertThat(response.name()).isEqualTo("Cafe");
        assertThat(response.description()).isEqualTo("Pacote 1kg");
        assertThat(response.price()).isEqualByComparingTo("29.90");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    /** O vendedor enxerga produto DISCONTINUED (D-24). */
    @Test
    void getByIdOfDiscontinuedProductReturnsItToTheSeller() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product("OLD-1", ProductStatus.DISCONTINUED)));

        ProductResponse response = service().getById(id, true);

        assertThat(response.sku()).isEqualTo("OLD-1");
        assertThat(response.status()).isEqualTo("DISCONTINUED");
    }

    /** Para o comprador, DISCONTINUED é indistinguível de inexistente (D-24). */
    @Test
    void getByIdOfDiscontinuedProductIsNotFoundForTheBuyer() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product("OLD-2", ProductStatus.DISCONTINUED)));

        assertThatThrownBy(() -> service().getById(id, false)).isInstanceOf(ProductNotFoundException.class);
    }

    /** Produto ativo continua visível ao comprador. */
    @Test
    void getByIdOfActiveProductReturnsItToTheBuyer() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.of(product("ACT-1", ProductStatus.ACTIVE)));

        assertThat(service().getById(id, false).sku()).isEqualTo("ACT-1");
    }

    /** Id inexistente vira 404 para qualquer papel. */
    @Test
    void getByIdOfUnknownProductThrowsProductNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(id, true)).isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> service().getById(id, false)).isInstanceOf(ProductNotFoundException.class);
    }

    /** A listagem do comprador filtra por ACTIVE no repositório e nunca lê o catálogo inteiro. */
    @Test
    void listForTheBuyerQueriesOnlyActiveProductsAndNeverFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Product> page = new PageImpl<>(List.of(product("ACT-2", ProductStatus.ACTIVE)), pageable, 1);
        when(productRepository.findByStatus(ProductStatus.ACTIVE, pageable)).thenReturn(page);

        Page<ProductResponse> result = service().list(pageable, false);

        assertThat(result.getContent()).extracting(ProductResponse::sku).containsExactly("ACT-2");
        verify(productRepository, never()).findAll(any(Pageable.class));
    }

    /** A listagem do vendedor traz todos os status e não aplica o filtro de ACTIVE. */
    @Test
    void listForTheSellerUsesFindAllAndNeverFiltersByStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Product> page = new PageImpl<>(List.of(
                product("ACT-3", ProductStatus.ACTIVE), product("OLD-3", ProductStatus.DISCONTINUED)), pageable, 2);
        when(productRepository.findAll(pageable)).thenReturn(page);

        Page<ProductResponse> result = service().list(pageable, true);

        assertThat(result.getContent()).extracting(ProductResponse::sku).containsExactly("ACT-3", "OLD-3");
        verify(productRepository, never()).findByStatus(any(), any());
    }

    /** A retirada do produto muda o status para DISCONTINUED. */
    @Test
    void changeStatusToDiscontinuedUpdatesTheProductStatus() {
        UUID id = UUID.randomUUID();
        Product existing = product("ACT-4", ProductStatus.ACTIVE);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));

        ProductResponse response = service().changeStatus(id, ProductStatus.DISCONTINUED);

        assertThat(existing.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
        assertThat(response.status()).isEqualTo("DISCONTINUED");
    }

    /** Atualizar produto inexistente é 404 e nada é gravado. */
    @Test
    void updateOfUnknownProductThrowsProductNotFound() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(id, new UpdateProductRequest("N", "d", new BigDecimal("1.00"))))
                .isInstanceOf(ProductNotFoundException.class);
        verify(productRepository, never()).save(any(Product.class));
    }

    /** A atualização muda nome, descrição e preço, mas não SKU nem status. */
    @Test
    void updateChangesDetailsButKeepsSkuAndStatus() {
        UUID id = UUID.randomUUID();
        Product existing = product("ACT-5", ProductStatus.ACTIVE);
        when(productRepository.findById(id)).thenReturn(Optional.of(existing));

        ProductResponse response = service().update(id, new UpdateProductRequest("Novo", "nova", new BigDecimal("21.50")));

        assertThat(response.name()).isEqualTo("Novo");
        assertThat(response.price()).isEqualByComparingTo("21.50");
        assertThat(response.sku()).isEqualTo("ACT-5");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }
}

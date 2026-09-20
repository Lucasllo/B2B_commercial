package com.orderflow.catalog.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    // Listagem paginada via Pageable/Page<T> do Spring Data — nunca LIMIT/OFFSET nem contagem
    // total à mão (D-24, D-26).
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
}

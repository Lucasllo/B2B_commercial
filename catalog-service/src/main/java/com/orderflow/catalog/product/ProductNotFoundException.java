package com.orderflow.catalog.product;

/**
 * Lançada quando um {@code productId} de path não corresponde a nenhum produto persistido, ou
 * quando o produto está {@code DISCONTINUED} e o chamador não é SELLER_ADMIN (um produto fora do
 * catálogo é indistinguível de inexistente para o comprador, plano 02-01 Task 3).
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}

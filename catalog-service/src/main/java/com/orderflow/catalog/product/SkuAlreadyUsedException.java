package com.orderflow.catalog.product;

/**
 * Lançada quando um SKU já usado por outro produto é enviado na criação — a constraint
 * {@code UNIQUE} do banco (products.sku) é a garantia real; esta exceção cobre o caminho sem
 * corrida detectado por {@code existsBySku}. Mensagem curta, sem eco do SKU recebido.
 */
public class SkuAlreadyUsedException extends RuntimeException {

    public SkuAlreadyUsedException(String message) {
        super(message);
    }
}

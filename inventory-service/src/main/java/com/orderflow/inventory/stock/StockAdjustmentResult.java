package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.StockResponse;

/**
 * Tipo interno usado so entre {@link InventoryService} e {@link InventoryController} para levar a
 * quantidade anterior ao ajuste ate o ponto de publicacao do evento — sem expor esse campo no
 * contrato REST publico de {@link StockResponse}, que ja e documentado e consumido
 * (03-RESEARCH.md Pitfall C). Nunca serializado diretamente numa resposta HTTP.
 */
public record StockAdjustmentResult(StockResponse stock, int previousQuantityOnHand) {
}

package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.StockResponse;

import java.time.Instant;

/**
 * Tipo interno usado so entre {@link InventoryService} e {@link InventoryController} para levar a
 * quantidade anterior ao ajuste ate o ponto de publicacao do evento — sem expor esse campo no
 * contrato REST publico de {@link StockResponse}, que ja e documentado e consumido
 * (03-RESEARCH.md Pitfall C). Nunca serializado diretamente numa resposta HTTP.
 *
 * <p>{@code adjustedAt} e capturado dentro da mesma tentativa transacional que gravou o ajuste
 * (logo apos o {@code saveAndFlush}), nunca no momento da publicacao do evento — que roda fora da
 * transacao, depois do commit e do retorno do proxy Spring. Sem isso, dois {@code PUT} concorrentes
 * no mesmo produto podem inverter a ordem em que os eventos chegam ao publicador em relacao a
 * ordem em que as transacoes de fato commitaram, e o historico (ordenado por {@code occurredAt} no
 * notification-service) mostraria o ajuste mais novo antes do mais antigo (WR-04).
 */
public record StockAdjustmentResult(StockResponse stock, int previousQuantityOnHand, Instant adjustedAt) {
}

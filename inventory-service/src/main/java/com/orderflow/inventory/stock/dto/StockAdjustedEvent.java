package com.orderflow.inventory.stock.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Duplica por contrato JSON o {@code record} equivalente do {@code notification-service} — sem
 * modulo Maven compartilhado (03-RESEARCH.md §Alternatives Considered). Os nomes e a ordem dos
 * campos sao exatamente os da linha {@code NOTIFICATION_EVENT_CONTRACT=} registrada em
 * {@code 03-01-SUMMARY.md}.
 *
 * <p>O {@code eventId} e gerado uma unica vez por ajuste, no momento da publicacao (nunca o
 * identificador de mensagem do SQS, que muda a cada entrega) — e o que o consumidor usa para
 * distinguir reentrega da mesma mensagem (mesmo id, sobrescreve) de um ajuste novo (id novo, linha
 * nova no historico) — D-33.
 */
public record StockAdjustedEvent(
        UUID eventId,
        String eventType,
        UUID productId,
        int previousQuantityOnHand,
        int newQuantityOnHand,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "STOCK_ADJUSTED";

    /**
     * {@code occurredAt} e recebido de fora (capturado pelo chamador dentro da tentativa
     * transacional que gravou o ajuste), e nao gerado aqui com {@code Instant.now()} — este metodo
     * roda no momento da publicacao, depois do commit, e sob concorrencia essa hora pode nao
     * refletir a ordem real em que os ajustes foram commitados (WR-04).
     */
    public static StockAdjustedEvent of(UUID productId, int previousQuantityOnHand, int newQuantityOnHand,
                                         Instant occurredAt) {
        return new StockAdjustedEvent(
                UUID.randomUUID(), EVENT_TYPE, productId, previousQuantityOnHand, newQuantityOnHand, occurredAt);
    }
}

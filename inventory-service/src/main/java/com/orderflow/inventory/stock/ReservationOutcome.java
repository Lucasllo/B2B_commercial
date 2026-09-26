package com.orderflow.inventory.stock;

import com.orderflow.inventory.saga.messaging.dto.ReservationFailureLine;

import java.util.List;

/**
 * Resultado interno de {@link InventoryService#reserveAll}, devolvido ao chamador ({@code
 * ReservationCommandListener}) — o evento de outbox (sucesso ou falha) já foi gravado dentro da
 * mesma transação antes deste record ser criado; este valor existe só para o chamador saber o que
 * aconteceu (ex.: log/teste), nunca para decidir se deve tentar de novo.
 */
public record ReservationOutcome(boolean reserved, String reasonCode, List<ReservationFailureLine> failures) {
}

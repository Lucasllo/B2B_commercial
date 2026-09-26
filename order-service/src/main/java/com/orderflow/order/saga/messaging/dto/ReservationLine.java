package com.orderflow.order.saga.messaging.dto;

import java.util.UUID;

/**
 * Um item do comando {@link ReserveStockCommand} — {@code SAGA_MESSAGE_CONTRACT}. Duplicado por
 * contrato JSON no inventory-service (D-62), sem módulo compartilhado — o autonomia de cada
 * serviço importa mais que evitar esta pequena repetição (mesmo padrão do {@code TestJwt} copiado
 * entre módulos).
 */
public record ReservationLine(UUID productId, int quantity) {
}

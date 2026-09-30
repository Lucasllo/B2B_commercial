package com.orderflow.order.shipping;

import java.util.UUID;

/**
 * Costura de integração com a transportadora (D-71, D-72): é o ponto onde uma API real de
 * transportadora entraria. A implementação desta fase, {@link SimulatedCarrierGateway}, é interna,
 * determinística, sem rede e nunca falha (D-41, D-72) — por isso não há circuit breaker, retry nem
 * falha simulada aqui. A atribuição acontece DENTRO da transação do {@code CONFIRMED}, sob a trava
 * de linha do pedido: qualquer exceção ou espera de rede ali reentregaria o mesmo {@code
 * StockReserved} indefinidamente.
 *
 * <p>É uma SIMULAÇÃO: o projeto não integra com nenhuma transportadora real, e o código devolvido
 * não é consultável em serviço algum.
 */
public interface CarrierGateway {

    /**
     * Atribui transportadora e código de rastreio ao pedido. Para o mesmo {@code orderId} devolve
     * sempre o mesmo resultado (idempotência sob reentrega) e nunca lança exceção.
     */
    CarrierAssignment assign(UUID orderId);
}

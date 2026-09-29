package com.orderflow.order.saga;

import com.orderflow.order.order.Order;
import com.orderflow.order.order.OrderItem;
import com.orderflow.order.saga.messaging.dto.ReservationFailureLine;
import com.orderflow.order.saga.messaging.dto.StockReservationFailedEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta o texto legível de {@code cancellation_reason} (D-56, {@code
 * CANCELLATION_REASON_TEMPLATE}) a partir de um modelo FIXO no servidor — nunca texto livre vindo
 * da mensagem da fila (T-05-14, Information Disclosure). O único dado externo que entra no texto é
 * o {@code sku} (resolvido do próprio snapshot do item do pedido, já gravado por este serviço) e os
 * números {@code available}/{@code requested} do evento — nunca uma mensagem de exceção nem
 * qualquer outro campo de texto livre. Truncado em 500 caracteres (limite da coluna {@code
 * cancellation_reason}), terminando em {@code …}.
 */
public final class CancellationReasons {

    private static final int MAX_LENGTH = 500;
    private static final String TRUNCATION_SUFFIX = "…";

    private CancellationReasons() {
    }

    /**
     * @param order usado só para resolver o {@code sku} de cada {@code productId} nas falhas — o
     *              snapshot do item ({@link OrderItem#getSku()}) é um valor seguro, gravado por
     *              este próprio serviço na criação do pedido, nunca o que a mensagem trouxe.
     */
    public static String forFailure(String reasonCode, List<ReservationFailureLine> failures, Order order) {
        String text = switch (reasonCode) {
            case StockReservationFailedEvent.INSUFFICIENT_STOCK ->
                    "Estoque insuficiente: " + describeFailures(failures, order);
            case StockReservationFailedEvent.PRODUCT_NOT_STOCKED ->
                    "Produto sem estoque cadastrado: " + describeFailures(failures, order);
            case StockReservationFailedEvent.RESERVATION_CANCELLED ->
                    "Reserva de estoque cancelada antes de ser processada pelo estoque";
            default -> throw new IllegalArgumentException("reasonCode desconhecido: " + reasonCode);
        };
        return truncate(text);
    }

    /**
     * Texto fixo do cancelamento por timeout da saga (D-63, {@code SAGA_TIMEOUT_DEFAULTS}) — não
     * depende de {@code failures} (não existe nenhuma: o estoque simplesmente não respondeu a
     * tempo), por isso não passa por {@link #truncate(String)} (já é curto o bastante).
     */
    public static String forTimeout() {
        return "Reserva de estoque não confirmada dentro do prazo — pedido cancelado por tempo esgotado";
    }

    private static String describeFailures(List<ReservationFailureLine> failures, Order order) {
        Map<UUID, String> skuByProductId = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getSku, (first, second) -> first));
        return failures.stream()
                .map(failure -> "produto " + skuByProductId.getOrDefault(failure.productId(), failure.productId().toString())
                        + " — disponível " + failure.available() + ", solicitado " + failure.requested())
                .collect(Collectors.joining("; "));
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LENGTH - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;
    }
}

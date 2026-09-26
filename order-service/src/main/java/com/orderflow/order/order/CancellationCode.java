package com.orderflow.order.order;

/**
 * Os quatro códigos do {@code CHECK chk_orders_cancellation_code}
 * ({@code V2__order_reservation_saga.sql}) — só {@link #INSUFFICIENT_STOCK}, {@link
 * #PRODUCT_NOT_STOCKED} e {@link #RESERVATION_CANCELLED} são gravados nesta fase (05-03);
 * {@link #RESERVATION_TIMEOUT} entra em 05-04. O nome do enum é gravado literal na coluna
 * {@code cancellation_code} ({@code @Enumerated(EnumType.STRING)}), e é exatamente o {@code
 * reasonCode} do contrato {@code StockReservationFailed} (D-53, D-56).
 */
public enum CancellationCode {
    INSUFFICIENT_STOCK,
    PRODUCT_NOT_STOCKED,
    RESERVATION_CANCELLED,
    RESERVATION_TIMEOUT
}

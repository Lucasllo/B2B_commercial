package com.orderflow.order.credit;

import java.math.BigDecimal;

/**
 * Regra de fronteira do crédito (D-36): exposição acumulada + total do novo pedido comparado ao
 * limite por {@code compareTo}, nunca {@code equals} — {@code equals} também compara a escala do
 * {@link BigDecimal} (04-RESEARCH.md Common Pitfall 2). A igualdade aprova.
 */
public final class CreditPolicy {

    private CreditPolicy() {
    }

    public static boolean fitsWithinLimit(BigDecimal exposure, BigDecimal orderTotal, BigDecimal creditLimit) {
        return exposure.add(orderTotal).compareTo(creditLimit) <= 0;
    }
}

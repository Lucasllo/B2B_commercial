package com.orderflow.order.observability;

import org.slf4j.MDC;

/**
 * Escopo de MDC do Correlation-ID neste serviço (D-93, D-97, T-07-01). Copiado por serviço, sem
 * módulo comum — a mesma decisão que já duplica o outbox (D-62).
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    public static final String SQS_ATTRIBUTE = "correlationId";

    private CorrelationContext() {
    }

    public static Scope open(String raw) {
        return () -> {
        };
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

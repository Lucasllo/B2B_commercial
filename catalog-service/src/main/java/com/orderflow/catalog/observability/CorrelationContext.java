package com.orderflow.catalog.observability;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Escopo de MDC do Correlation-ID neste serviço (D-93, D-96, D-97, T-07-18). O header HTTP e o atributo
 * SQS são fronteiras de confiança: só entra no log o que casa {@code [A-Za-z0-9-]{1,64}}; qualquer
 * outra coisa (nulo, CR/LF, espaço, mais de 64 caracteres) vira um UUID novo, para ninguém injetar
 * uma linha de log.
 *
 * <p>{@link #open} devolve um {@link Scope} que restaura o valor anterior do MDC ao fechar — a
 * thread do pool, do listener e do relay é reutilizada e não pode levar o ID do trabalho anterior.
 * Copiado por serviço, sem módulo comum: a mesma decisão que já duplica o outbox (D-62).
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    public static final String SQS_ATTRIBUTE = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private CorrelationContext() {
    }

    /**
     * Valida {@code raw} ou gera um UUID, põe o resultado no MDC e devolve o escopo que restaura o
     * valor que estava lá antes.
     */
    public static Scope open(String raw) {
        String previous = MDC.get(MDC_KEY);
        String id = (raw != null && VALID.matcher(raw).matches()) ? raw : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
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

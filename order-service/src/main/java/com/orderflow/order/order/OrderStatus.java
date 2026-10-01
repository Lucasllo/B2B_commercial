package com.orderflow.order.order;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Os 9 estados da ORD-10, na mesma ordem declarada pelo {@code CHECK} de
 * {@code V2__order_reservation_saga.sql} (o {@code CHECK} nasceu em {@code V1__init_order_schema.sql}
 * com 8 valores; a Fase 5 o larga e recria com {@code RESERVING} incluído, D-49). Todos os nove são
 * alcançados por código a partir da Fase 6 ({@code SHIPPED}/{@code DELIVERED} pelos endpoints de
 * expedição e entrega).
 *
 * <p>{@code APPROVED} é, a partir da Fase 5, um passo lógico da decisão (D-50) — registrado em
 * {@code decidedBy}/{@code decidedAt}/{@code reason} — nunca um estado em que um pedido novo
 * repousa: a entrada em aprovação (automática ou manual) grava direto {@code RESERVING} na mesma
 * transação que insere o comando de reserva no outbox (D-48). O valor {@code APPROVED} permanece
 * na tabela de transições como esse passo lógico e por pedidos históricos anteriores à Fase 5.
 *
 * <p><b>Fonte única de verdade das transições (D-83):</b> {@link #transitions()} lista as 9
 * arestas permitidas e é o que o diagrama do README espelha. {@code CANCELLED} só é alcançável pela
 * saga (D-77) — não há endpoint de cancelamento. {@code SHIPPED} e {@code DELIVERED} continuam em
 * {@link #CREDIT_CONSUMING} (D-37): só a entrega não libera crédito.
 */
public enum OrderStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    RESERVING,
    CONFIRMED,
    CANCELLED,
    SHIPPED,
    DELIVERED;

    /**
     * Consomem crédito (D-37, D-52): {@code APPROVED}, {@code RESERVING}, {@code CONFIRMED},
     * {@code SHIPPED} e {@code DELIVERED}. {@code PENDING_APPROVAL} não consome enquanto espera;
     * {@code REJECTED} e {@code CANCELLED} liberam. Conjunto único — a lista de status que consome
     * crédito existe num único lugar, nunca duplicada onde a soma de exposição é calculada.
     */
    public static final Set<OrderStatus> CREDIT_CONSUMING =
            Set.of(APPROVED, RESERVING, CONFIRMED, SHIPPED, DELIVERED);

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = buildTransitions();

    private static Map<OrderStatus, Set<OrderStatus>> buildTransitions() {
        Map<OrderStatus, Set<OrderStatus>> table = new EnumMap<>(OrderStatus.class);
        table.put(CREATED, EnumSet.of(PENDING_APPROVAL, APPROVED));
        table.put(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED));
        // APPROVED -> RESERVING é o APPROVED lógico da D-50: mesma transação, nunca observado em repouso.
        table.put(APPROVED, EnumSet.of(RESERVING));
        table.put(REJECTED, EnumSet.noneOf(OrderStatus.class));
        table.put(RESERVING, EnumSet.of(CONFIRMED, CANCELLED));
        table.put(CONFIRMED, EnumSet.of(SHIPPED));
        table.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        table.put(SHIPPED, EnumSet.of(DELIVERED));
        table.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        table.replaceAll((status, targets) -> Collections.unmodifiableSet(targets));
        return Collections.unmodifiableMap(table);
    }

    /** {@code true} se a tabela de {@link #transitions()} permite a aresta {@code this -> target}. */
    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /**
     * Visão imutável da tabela de transições — os 9 estados como chave, os terminais ({@code
     * REJECTED}, {@code CANCELLED}, {@code DELIVERED}) com conjunto vazio. É a lista que o diagrama
     * do README espelha.
     */
    public static Map<OrderStatus, Set<OrderStatus>> transitions() {
        return TRANSITIONS;
    }
}

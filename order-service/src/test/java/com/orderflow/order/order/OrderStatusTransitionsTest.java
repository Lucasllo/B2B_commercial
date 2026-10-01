package com.orderflow.order.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Trava a tabela única de transições (D-83, ORD-10): os 81 pares (de, para) de {@link
 * OrderStatus#values()} comparados com as 9 arestas documentadas, escritas literalmente aqui — se
 * alguém acrescentar ou remover uma aresta em {@link OrderStatus}, este teste falha.
 */
class OrderStatusTransitionsTest {

    private static final Map<OrderStatus, Set<OrderStatus>> DOCUMENTED = documented();

    private static Map<OrderStatus, Set<OrderStatus>> documented() {
        Map<OrderStatus, Set<OrderStatus>> map = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            map.put(status, EnumSet.noneOf(OrderStatus.class));
        }
        map.get(OrderStatus.CREATED).add(OrderStatus.PENDING_APPROVAL);
        map.get(OrderStatus.CREATED).add(OrderStatus.APPROVED);
        map.get(OrderStatus.PENDING_APPROVAL).add(OrderStatus.APPROVED);
        map.get(OrderStatus.PENDING_APPROVAL).add(OrderStatus.REJECTED);
        map.get(OrderStatus.APPROVED).add(OrderStatus.RESERVING);
        map.get(OrderStatus.RESERVING).add(OrderStatus.CONFIRMED);
        map.get(OrderStatus.RESERVING).add(OrderStatus.CANCELLED);
        map.get(OrderStatus.CONFIRMED).add(OrderStatus.SHIPPED);
        map.get(OrderStatus.SHIPPED).add(OrderStatus.DELIVERED);
        return map;
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> allPairs() {
        List<org.junit.jupiter.params.provider.Arguments> pairs = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                pairs.add(arguments(from, to, DOCUMENTED.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("allPairs")
    void canTransitionToMatchesTheDocumentedEdgeForEveryOneOfThe81Pairs(
            OrderStatus from, OrderStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @Test
    void thereAreExactly81PairsAndNineDocumentedEdges() {
        assertThat(allPairs().count()).isEqualTo(81);
        assertThat(DOCUMENTED.values().stream().mapToInt(Set::size).sum()).isEqualTo(9);
    }

    @Test
    void transitionsExposesAllNineStatesAsKeysWithNineEdgesNoSelfLoopsAndIsImmutable() {
        Map<OrderStatus, Set<OrderStatus>> transitions = OrderStatus.transitions();

        assertThat(transitions.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
        assertThat(transitions.values().stream().mapToInt(Set::size).sum()).isEqualTo(9);
        transitions.forEach((from, targets) -> assertThat(targets).doesNotContain(from));
        transitions.forEach((from, targets) -> assertThat(targets).isEqualTo(DOCUMENTED.get(from)));

        assertThatThrownBy(() -> transitions.put(OrderStatus.CREATED, Set.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> transitions.get(OrderStatus.CREATED).add(OrderStatus.DELIVERED))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> transitions.get(OrderStatus.DELIVERED).add(OrderStatus.CREATED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectedCancelledAndDeliveredAreTerminalAndEveryOtherStateHasAWayOut() {
        Set<OrderStatus> terminal = EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.DELIVERED);
        for (OrderStatus status : OrderStatus.values()) {
            if (terminal.contains(status)) {
                assertThat(OrderStatus.transitions().get(status)).as("%s is terminal", status).isEmpty();
            } else {
                assertThat(OrderStatus.transitions().get(status)).as("%s has an exit", status).isNotEmpty();
            }
        }
    }
}

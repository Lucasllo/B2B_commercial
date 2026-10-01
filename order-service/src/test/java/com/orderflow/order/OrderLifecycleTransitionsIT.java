package com.orderflow.order;

import com.orderflow.order.order.OrderStatus;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova de que a API responde exatamente conforme a tabela de transições (ORD-10, D-77, D-83,
 * critério 2 do ROADMAP): para cada um dos 9 status x as 4 ações do vendedor ({@code approve},
 * {@code reject}, {@code ship}, {@code deliver}) a resposta é 200 só quando o pedido está na origem
 * da aresta que a ação representa em {@link OrderStatus#transitions()}, e 409 em todos os outros
 * casos, sem alterar o pedido. Os pedidos são semeados por JDBC direto no status desejado (inclusive
 * os que a API não alcança sozinha em um teste curto, como CANCELLED e DELIVERED), com as colunas
 * que as CHECKs das migrações V2 e V3 exigem.
 */
class OrderLifecycleTransitionsIT extends AbstractIntegrationTest {

    /** A aresta que cada ação do vendedor representa na tabela de transições. */
    enum SellerAction {
        APPROVE(OrderStatus.PENDING_APPROVAL, OrderStatus.APPROVED, OrderStatus.RESERVING),
        REJECT(OrderStatus.PENDING_APPROVAL, OrderStatus.REJECTED, OrderStatus.REJECTED),
        SHIP(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.SHIPPED),
        DELIVER(OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.DELIVERED);

        /** Origem da aresta. */
        final OrderStatus from;
        /** Destino da aresta na tabela (usado na mensagem do 409 de ship/deliver). */
        final OrderStatus to;
        /** Status observável depois de um 200 — approve entra na saga e repousa em RESERVING (D-50). */
        final OrderStatus observedAfterSuccess;

        SellerAction(OrderStatus from, OrderStatus to, OrderStatus observedAfterSuccess) {
            this.from = from;
            this.to = to;
            this.observedAfterSuccess = observedAfterSuccess;
        }

        String path() {
            return name().toLowerCase();
        }
    }

    static Stream<Arguments> everyStatusTimesEveryAction() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(orderStatus -> Arrays.stream(SellerAction.values())
                        .map(action -> Arguments.of(orderStatus, action)));
    }

    @Test
    void everySellerActionEdgeIsAnEdgeOfTheTransitionTable() {
        for (SellerAction action : SellerAction.values()) {
            assertThat(OrderStatus.transitions().get(action.from))
                    .as("%s: %s -> %s must be in OrderStatus.transitions()", action, action.from, action.to)
                    .contains(action.to);
        }
        assertThat(OrderStatus.values()).hasSize(9);
        assertThat(everyStatusTimesEveryAction().count()).isEqualTo(36);
    }

    @ParameterizedTest(name = "{1} on an order in {0}")
    @MethodSource("everyStatusTimesEveryAction")
    void theApiAnswers200OnlyOnTheSourceOfTheActionsEdgeAnd409EverywhereElseWithoutChangingTheOrder(
            OrderStatus seededStatus, SellerAction action) throws Exception {
        UUID orderId = seedOrder(seededStatus);
        String sellerToken = TestJwt.sellerAdminToken();

        if (seededStatus == action.from) {
            MvcResult result = mockMvc.perform(request(action, orderId, sellerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(action.observedAfterSuccess.name()))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString()).contains(orderId.toString());
            assertThat(statusInDatabase(orderId)).isEqualTo(action.observedAfterSuccess);
        } else if (action == SellerAction.APPROVE || action == SellerAction.REJECT) {
            mockMvc.perform(request(action, orderId, sellerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("order_not_pending"));
            assertThat(statusInDatabase(orderId)).isEqualTo(seededStatus);
        } else {
            mockMvc.perform(request(action, orderId, sellerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("invalid_order_transition"))
                    .andExpect(jsonPath("$.message")
                            .value("Order cannot transition from " + seededStatus + " to " + action.to));
            assertThat(statusInDatabase(orderId)).isEqualTo(seededStatus);
        }
    }

    @Test
    void skippingStagesAndTouchingACancelledOrderAreBothConflicts() throws Exception {
        String sellerToken = TestJwt.sellerAdminToken();

        UUID created = seedOrder(OrderStatus.CREATED);
        mockMvc.perform(post("/orders/{orderId}/deliver", created)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_order_transition"))
                .andExpect(jsonPath("$.message").value("Order cannot transition from CREATED to DELIVERED"));

        UUID cancelled = seedOrder(OrderStatus.CANCELLED);
        for (String action : new String[] {"ship", "deliver"}) {
            mockMvc.perform(post("/orders/{orderId}/" + action, cancelled)
                            .header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("invalid_order_transition"));
        }
        assertThat(statusInDatabase(cancelled)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void shipAndDeliverOnAnUnknownOrderAreA404OrderNotFound() throws Exception {
        String sellerToken = TestJwt.sellerAdminToken();
        for (String action : new String[] {"ship", "deliver"}) {
            mockMvc.perform(post("/orders/{orderId}/" + action, UUID.randomUUID())
                            .header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("order_not_found"));
        }
    }

    @Test
    void aBuyerCannotShipOrDeliverAndAnonymousCallersAreUnauthorized() throws Exception {
        UUID confirmed = seedOrder(OrderStatus.CONFIRMED);
        UUID shipped = seedOrder(OrderStatus.SHIPPED);
        UUID companyId = UUID.randomUUID();

        mockMvc.perform(post("/orders/{orderId}/ship", confirmed)
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(companyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(post("/orders/{orderId}/deliver", shipped)
                        .header("Authorization", "Bearer " + TestJwt.buyerToken(companyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(post("/orders/{orderId}/ship", confirmed)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/orders/{orderId}/deliver", shipped)).andExpect(status().isUnauthorized());

        assertThat(statusInDatabase(confirmed)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(statusInDatabase(shipped)).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void anOrderIdThatIsNotAUuidIsA400InvalidParameter() throws Exception {
        String sellerToken = TestJwt.sellerAdminToken();
        for (String action : new String[] {"ship", "deliver"}) {
            mockMvc.perform(post("/orders/nao-e-uuid/" + action)
                            .header("Authorization", "Bearer " + sellerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_parameter"));
        }
    }

    // -----------------------------------------------------------------------------------------
    // Suporte
    // -----------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder request(SellerAction action, UUID orderId, String sellerToken) {
        MockHttpServletRequestBuilder builder = post("/orders/{orderId}/" + action.path(), orderId)
                .header("Authorization", "Bearer " + sellerToken);
        if (action == SellerAction.REJECT) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"motivo de teste\"}");
        }
        return builder;
    }

    private OrderStatus statusInDatabase(UUID orderId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT status FROM \"order\".orders WHERE id = ?", String.class, orderId);
        return OrderStatus.valueOf(value);
    }

    /**
     * Semeia um pedido (uma empresa por pedido, para não misturar crédito entre casos) direto no
     * status pedido, com as colunas que as CHECKs exigem para aquele status. Pedidos em RESERVING
     * recebem {@code reservation_started_at = agora}: o prazo do job de timeout nos testes é de 10
     * minutos, então nenhum é cancelado durante a execução.
     */
    private UUID seedOrder(OrderStatus status) {
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean decided = status != OrderStatus.CREATED && status != OrderStatus.PENDING_APPROVAL;
        boolean confirmed = status == OrderStatus.CONFIRMED || status == OrderStatus.SHIPPED
                || status == OrderStatus.DELIVERED;
        boolean shipped = status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
        boolean delivered = status == OrderStatus.DELIVERED;
        boolean cancelled = status == OrderStatus.CANCELLED;
        boolean reservationStarted = status == OrderStatus.RESERVING || confirmed || cancelled;

        jdbcTemplate.update(
                "INSERT INTO \"order\".orders (id, company_id, status, total, created_by, created_at, "
                        + "decided_by, decided_at, reason, reservation_started_at, "
                        + "cancellation_code, cancellation_reason, cancelled_at, "
                        + "confirmed_at, carrier, tracking_code, "
                        + "shipped_at, shipped_by, delivered_at, delivered_by) "
                        + "VALUES (?, ?, ?, ?, 'buyer-1', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, companyId, status.name(), new BigDecimal("100.00"), now,
                decided ? "SYSTEM" : null, decided ? now : null, decided ? "dentro do limite de crédito" : null,
                reservationStarted ? now : null,
                cancelled ? "INSUFFICIENT_STOCK" : null,
                cancelled ? "Estoque insuficiente: produto SKU-X — disponível 0, solicitado 1" : null,
                cancelled ? now : null,
                confirmed ? now : null, confirmed ? "Norte Entregas" : null, confirmed ? "AB123456785BR" : null,
                shipped ? now : null, shipped ? "seller-1" : null,
                delivered ? now : null, delivered ? "seller-1" : null);

        jdbcTemplate.update(
                "INSERT INTO \"order\".order_items "
                        + "(id, order_id, line_number, product_id, sku, name, unit_price, quantity, subtotal) "
                        + "VALUES (?, ?, 1, ?, 'SKU-X', 'Item X', 100.00, 1, 100.00)",
                UUID.randomUUID(), orderId, UUID.randomUUID());
        return orderId;
    }
}

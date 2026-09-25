package com.orderflow.order;

import com.orderflow.order.credit.CompanyCreditLocker;
import com.orderflow.order.order.OrderRepository;
import com.orderflow.order.order.OrderStatus;
import com.orderflow.order.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.transaction.IllegalTransactionStateException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova a soma de exposição (D-37) e a serialização estrutural da trava (D-40) — complementa
 * {@link CreditLimitBoundaryConcurrencyIT}, que prova a mesma regra sob disputa real.
 */
class CreditLockAndExposureIT extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CompanyCreditLocker companyCreditLocker;

    @Test
    void sumTotalByCompanyIdAndStatusInSumsExactlyTheCreditConsumingStatuses() {
        UUID companyId = UUID.randomUUID();
        // Um pedido por estado, na ordem da ORD-10, com totais 1, 2, 4, 8, 16, 32, 64 e 128.
        insertOrder(companyId, OrderStatus.CREATED, "1.00");
        insertOrder(companyId, OrderStatus.PENDING_APPROVAL, "2.00");
        insertOrder(companyId, OrderStatus.APPROVED, "4.00");
        insertOrder(companyId, OrderStatus.REJECTED, "8.00");
        insertOrder(companyId, OrderStatus.CONFIRMED, "16.00");
        insertOrder(companyId, OrderStatus.CANCELLED, "32.00");
        insertOrder(companyId, OrderStatus.SHIPPED, "64.00");
        insertOrder(companyId, OrderStatus.DELIVERED, "128.00");

        // APPROVED + CONFIRMED + SHIPPED + DELIVERED = 4 + 16 + 64 + 128 = 212.
        BigDecimal exposure = orderRepository.sumTotalByCompanyIdAndStatusIn(companyId, OrderStatus.CREDIT_CONSUMING);
        assertThat(exposure).isEqualByComparingTo("212.00");

        BigDecimal noOrdersExposure = orderRepository.sumTotalByCompanyIdAndStatusIn(
                UUID.randomUUID(), OrderStatus.CREDIT_CONSUMING);
        assertThat(noOrdersExposure == null || noOrdersExposure.compareTo(BigDecimal.ZERO) == 0).isTrue();
    }

    @Test
    void creditConsumingIsExactlyApprovedConfirmedShippedDelivered() {
        assertThat(OrderStatus.CREDIT_CONSUMING).containsExactlyInAnyOrder(
                OrderStatus.APPROVED, OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    @Test
    void insertingAnUnknownStatusIsRejectedByTheDatabaseCheckConstraint() {
        UUID orderId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO \"order\".orders (id, company_id, status, total, created_by, created_at) "
                        + "VALUES (?, ?, 'FOO', 1.00, 'tester', now())",
                orderId, companyId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acquiringTheLockOutsideATransactionFailsHigh() {
        assertThatThrownBy(() -> companyCreditLocker.acquire(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void confirmedOrderInsertedDirectlyConsumesCreditAndRejectedOrderOfAnotherCompanyDoesNot() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String buyerToken = TestJwt.buyerToken(companyId);
        stub().registerCreditLimit(companyId, new BigDecimal("100.00"));
        stub().registerProduct(productId, "SKU-C1", "Componente", new BigDecimal("20.00"), "ACTIVE");

        // CONFIRMED — estado que esta fase ainda não alcança por código, mas já consome (D-37).
        insertOrder(companyId, OrderStatus.CONFIRMED, "90.00");

        // 90.00 (já consumido) + 20.00 = 110.00 > 100.00 → PENDING_APPROVAL.
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        UUID otherCompanyId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        String otherBuyerToken = TestJwt.buyerToken(otherCompanyId);
        stub().registerCreditLimit(otherCompanyId, new BigDecimal("100.00"));
        stub().registerProduct(otherProductId, "SKU-C2", "Componente 2", new BigDecimal("100.00"), "ACTIVE");

        // Pedido REJECTED de outra empresa — nunca consome, e nunca vaza entre empresas.
        insertOrder(otherCompanyId, OrderStatus.REJECTED, "500.00");

        // 0.00 (REJECTED não consome) + 100.00 = 100.00 → APPROVED (igualdade aprova, D-36).
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + otherBuyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","quantity":1}]}
                                """.formatted(otherProductId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private void insertOrder(UUID companyId, OrderStatus status, String total) {
        jdbcTemplate.update(
                "INSERT INTO \"order\".orders (id, company_id, status, total, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, 'tester', now())",
                UUID.randomUUID(), companyId, status.name(), new BigDecimal(total));
    }
}

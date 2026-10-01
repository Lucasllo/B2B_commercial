package com.orderflow.notification;

import com.nimbusds.jwt.JWTClaimsSet;
import com.orderflow.notification.history.NotificationService;
import com.orderflow.notification.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plano 06-04 Task 3: prova de autorizacao da linha do tempo do pedido (IDOR, T-06-14). Os eventos
 * sao gravados chamando {@code notificationService.record(json)} direto contra o DynamoDB real do
 * LocalStack — sem fila no meio, para que a leitura seja deterministica.
 */
class OrderTimelineControllerIT extends AbstractIntegrationTest {

    private static final String NOT_FOUND_ERROR = "order_not_found";
    private static final String NOT_FOUND_MESSAGE = "Order not found";

    @Autowired
    private NotificationService notificationService;

    private static String authHeader(String token) {
        return "Bearer " + token;
    }

    private void recordEvent(String type, UUID orderId, UUID companyId, String occurredAt, String extras) {
        notificationService.record("""
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","orderId":"%s","companyId":"%s"%s}
                """.formatted(UUID.randomUUID(), type, occurredAt, orderId, companyId, extras));
    }

    private UUID givenOrderWithThreeEvents(UUID companyId) {
        UUID orderId = UUID.randomUUID();
        recordEvent("ORDER_CREATED", orderId, companyId, "2026-09-30T12:00:00Z",
                ",\"createdBy\":\"buyer-1\",\"total\":40.00");
        recordEvent("ORDER_APPROVED", orderId, companyId, "2026-09-30T12:00:00Z", ",\"decidedBy\":\"SYSTEM\"");
        recordEvent("ORDER_CONFIRMED", orderId, companyId, "2026-09-30T12:00:02Z",
                ",\"carrier\":\"Expresso Cerrado\",\"trackingCode\":\"AB123456789BR\"");
        return orderId;
    }

    @Test
    void sellerAdminAndOwnerBuyerSeeTheTimelineButAnotherCompanyGets404() throws Exception {
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID orderId = givenOrderWithThreeEvents(companyA);

        mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", authHeader(TestJwt.sellerAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("ORDER_CREATED"))
                .andExpect(jsonPath("$[1].eventType").value("ORDER_APPROVED"))
                .andExpect(jsonPath("$[2].eventType").value("ORDER_CONFIRMED"));

        mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", authHeader(TestJwt.buyerToken(companyA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", authHeader(TestJwt.buyerToken(companyB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(NOT_FOUND_ERROR))
                .andExpect(jsonPath("$.message").value(NOT_FOUND_MESSAGE));
    }

    @Test
    void nonexistentOrderIs404ForBuyerWithBodyIdenticalToOtherCompanyAndEmptyListForSeller() throws Exception {
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID existingOrder = givenOrderWithThreeEvents(companyA);
        UUID missingOrder = UUID.randomUUID();

        MvcResult otherCompany = mockMvc.perform(get("/notifications/orders/" + existingOrder)
                        .header("Authorization", authHeader(TestJwt.buyerToken(companyB))))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult nonexistent = mockMvc.perform(get("/notifications/orders/" + missingOrder)
                        .header("Authorization", authHeader(TestJwt.buyerToken(companyB))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(NOT_FOUND_ERROR))
                .andReturn();

        assertThat(nonexistent.getResponse().getContentAsString())
                .isEqualTo(otherCompany.getResponse().getContentAsString());

        mockMvc.perform(get("/notifications/orders/" + missingOrder)
                        .header("Authorization", authHeader(TestJwt.sellerAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void productIdQueriedOnTheOrderRouteShowsNoStockEventsAndBuyerGets404() throws Exception {
        UUID productId = UUID.randomUUID();
        notificationService.record("""
                {"eventId":"%s","eventType":"STOCK_ADJUSTED","productId":"%s","previousQuantityOnHand":5,"newQuantityOnHand":12,"occurredAt":"2026-09-22T12:00:00Z"}
                """.formatted(UUID.randomUUID(), productId));

        mockMvc.perform(get("/notifications/orders/" + productId)
                        .header("Authorization", authHeader(TestJwt.sellerAdminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/notifications/orders/" + productId)
                        .header("Authorization", authHeader(TestJwt.buyerToken(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(NOT_FOUND_ERROR));
    }

    @Test
    void orderWithEventsFromTwoCompaniesIs404ForBuyerOfEitherOne() throws Exception {
        UUID companyA = UUID.randomUUID();
        UUID companyB = UUID.randomUUID();
        UUID orderId = givenOrderWithThreeEvents(companyA);
        // evento forjado: mesmo pedido, outra empresa (T-06-16)
        recordEvent("ORDER_SHIPPED", orderId, companyB, "2026-09-30T12:05:00Z", ",\"shippedBy\":\"intruso\"");

        for (UUID company : new UUID[]{companyA, companyB}) {
            mockMvc.perform(get("/notifications/orders/" + orderId)
                            .header("Authorization", authHeader(TestJwt.buyerToken(company))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value(NOT_FOUND_ERROR));
        }
    }

    @Test
    void missingTokenIs401BuyerWithoutCompanyClaimIs403AndNonUuidIs400() throws Exception {
        UUID orderId = givenOrderWithThreeEvents(UUID.randomUUID());

        mockMvc.perform(get("/notifications/orders/" + orderId))
                .andExpect(status().isUnauthorized());

        JWTClaimsSet noCompany = new JWTClaimsSet.Builder()
                .issuer("orderflow-auth-service")
                .subject(UUID.randomUUID().toString())
                .claim("role", "BUYER")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", authHeader(TestJwt.tokenSignedBy(TestJwt.RSA_KEY, noCompany))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        mockMvc.perform(get("/notifications/orders/nao-e-uuid")
                        .header("Authorization", authHeader(TestJwt.sellerAdminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_identifier"));
    }

    @Test
    void buyerStillCannotReadTheProductHistoryRoute() throws Exception {
        mockMvc.perform(get("/notifications/" + UUID.randomUUID())
                        .header("Authorization", authHeader(TestJwt.buyerToken(UUID.randomUUID()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void errorBodiesNeverLeakExceptionStackOrTableName() throws Exception {
        UUID orderId = givenOrderWithThreeEvents(UUID.randomUUID());

        MvcResult notFound = mockMvc.perform(get("/notifications/orders/" + orderId)
                        .header("Authorization", authHeader(TestJwt.buyerToken(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult forbidden = mockMvc.perform(get("/notifications/" + UUID.randomUUID())
                        .header("Authorization", authHeader(TestJwt.buyerToken(UUID.randomUUID()))))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult badRequest = mockMvc.perform(get("/notifications/orders/nao-e-uuid")
                        .header("Authorization", authHeader(TestJwt.sellerAdminToken())))
                .andExpect(status().isBadRequest())
                .andReturn();

        for (MvcResult result : new MvcResult[]{notFound, forbidden, badRequest}) {
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain("Exception")
                    .doesNotContain("at com.orderflow")
                    .doesNotContain("notification-history");
        }
    }
}

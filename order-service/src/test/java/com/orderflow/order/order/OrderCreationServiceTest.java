package com.orderflow.order.order;

import com.orderflow.order.client.AuthServiceClient;
import com.orderflow.order.client.AuthServiceUnavailableException;
import com.orderflow.order.client.CatalogServiceClient;
import com.orderflow.order.client.CatalogServiceUnavailableException;
import com.orderflow.order.client.dto.CatalogProductResponse;
import com.orderflow.order.order.dto.CreateOrderRequest;
import com.orderflow.order.order.dto.OrderItemRequest;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.InvalidOrderItemsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes unitários (JUnit + Mockito, sem contexto Spring, 04-02 Task 3) do orquestrador não
 * transacional — os dois clientes HTTP e {@link OrderService} são mocks; prova a ORDEM das
 * chamadas (D-44: nenhuma checagem cara acontece depois de uma recusa barata) sem precisar de
 * Postgres nem do stub HTTP.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    private static final String BEARER_TOKEN = "test-bearer-token";

    @Mock
    private CatalogServiceClient catalogServiceClient;

    @Mock
    private AuthServiceClient authServiceClient;

    @Mock
    private OrderService orderService;

    @Test
    void missingProductInCatalogThrowsInvalidOrderItemsExceptionWithoutCheckingCreditOrCreatingOrder() {
        OrderCreationService service = new OrderCreationService(catalogServiceClient, authServiceClient, orderService);
        UUID companyId = UUID.randomUUID();
        UUID missingProductId = UUID.randomUUID();
        CreateOrderRequest request = orderRequest(new OrderItemRequest(missingProductId, 1));

        when(catalogServiceClient.findOrderableProduct(eq(missingProductId), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(companyId, "buyer-1", BEARER_TOKEN, request))
                .isInstanceOf(InvalidOrderItemsException.class);

        verify(authServiceClient, never()).getCreditLimit(any(), anyString());
        verifyNoInteractions(orderService);
    }

    @Test
    void catalogServiceUnavailableOnSecondItemPropagatesWithoutCheckingCreditLimit() {
        OrderCreationService service = new OrderCreationService(catalogServiceClient, authServiceClient, orderService);
        UUID companyId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        CreateOrderRequest request = orderRequest(
                new OrderItemRequest(firstProductId, 1),
                new OrderItemRequest(secondProductId, 1));

        when(catalogServiceClient.findOrderableProduct(eq(firstProductId), anyString()))
                .thenReturn(Optional.of(new CatalogProductResponse(firstProductId, "SKU-1", "Item 1", new BigDecimal("10.00"), "ACTIVE")));
        when(catalogServiceClient.findOrderableProduct(eq(secondProductId), anyString()))
                .thenThrow(new CatalogServiceUnavailableException("catalog-service unavailable"));

        assertThatThrownBy(() -> service.create(companyId, "buyer-1", BEARER_TOKEN, request))
                .isInstanceOf(CatalogServiceUnavailableException.class);

        verify(authServiceClient, never()).getCreditLimit(any(), anyString());
        verifyNoInteractions(orderService);
    }

    @Test
    void authServiceUnavailablePropagatesWithoutCreatingOrder() {
        OrderCreationService service = new OrderCreationService(catalogServiceClient, authServiceClient, orderService);
        UUID companyId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CreateOrderRequest request = orderRequest(new OrderItemRequest(productId, 1));

        when(catalogServiceClient.findOrderableProduct(eq(productId), anyString()))
                .thenReturn(Optional.of(new CatalogProductResponse(productId, "SKU-1", "Item 1", new BigDecimal("10.00"), "ACTIVE")));
        when(authServiceClient.getCreditLimit(eq(companyId), anyString()))
                .thenThrow(new AuthServiceUnavailableException("auth-service unavailable"));

        assertThatThrownBy(() -> service.create(companyId, "buyer-1", BEARER_TOKEN, request))
                .isInstanceOf(AuthServiceUnavailableException.class);

        verifyNoInteractions(orderService);
    }

    @Test
    void happyPathBuildsPricedItemsInOrderWithCorrectSubtotalsAndForwardsCreditLimit() {
        OrderCreationService service = new OrderCreationService(catalogServiceClient, authServiceClient, orderService);
        UUID companyId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        CreateOrderRequest request = orderRequest(
                new OrderItemRequest(productA, 3),
                new OrderItemRequest(productB, 2));

        when(catalogServiceClient.findOrderableProduct(eq(productA), anyString()))
                .thenReturn(Optional.of(new CatalogProductResponse(productA, "SKU-A", "Item A", new BigDecimal("10.00"), "ACTIVE")));
        when(catalogServiceClient.findOrderableProduct(eq(productB), anyString()))
                .thenReturn(Optional.of(new CatalogProductResponse(productB, "SKU-B", "Item B", new BigDecimal("5.00"), "ACTIVE")));
        BigDecimal creditLimit = new BigDecimal("1000.00");
        when(authServiceClient.getCreditLimit(eq(companyId), anyString())).thenReturn(creditLimit);

        OrderResponse expectedResponse = new OrderResponse(UUID.randomUUID(), companyId, "APPROVED",
                new BigDecimal("40.00"), "buyer-1", null, "SYSTEM", null, null, List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PricedItem>> pricedItemsCaptor = ArgumentCaptor.forClass(List.class);
        when(orderService.createWithCreditCheck(eq(companyId), eq("buyer-1"), pricedItemsCaptor.capture(), eq(creditLimit)))
                .thenReturn(expectedResponse);

        OrderResponse response = service.create(companyId, "buyer-1", BEARER_TOKEN, request);

        assertThat(response).isEqualTo(expectedResponse);
        List<PricedItem> pricedItems = pricedItemsCaptor.getValue();
        assertThat(pricedItems).hasSize(2);
        assertThat(pricedItems.get(0).lineNumber()).isEqualTo(1);
        assertThat(pricedItems.get(0).productId()).isEqualTo(productA);
        assertThat(pricedItems.get(0).subtotal()).isEqualByComparingTo("30.00");
        assertThat(pricedItems.get(1).lineNumber()).isEqualTo(2);
        assertThat(pricedItems.get(1).productId()).isEqualTo(productB);
        assertThat(pricedItems.get(1).subtotal()).isEqualByComparingTo("10.00");
    }

    private CreateOrderRequest orderRequest(OrderItemRequest... items) {
        return new CreateOrderRequest(List.of(items));
    }
}

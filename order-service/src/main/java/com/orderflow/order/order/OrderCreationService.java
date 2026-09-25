package com.orderflow.order.order;

import com.orderflow.order.client.AuthServiceClient;
import com.orderflow.order.client.CatalogServiceClient;
import com.orderflow.order.client.dto.CatalogProductResponse;
import com.orderflow.order.order.dto.CreateOrderRequest;
import com.orderflow.order.order.dto.OrderItemRequest;
import com.orderflow.order.order.dto.OrderResponse;
import com.orderflow.order.order.exception.DuplicateOrderItemsException;
import com.orderflow.order.order.exception.InvalidOrderItemsException;
import com.orderflow.order.order.exception.OrderTotalOutOfRangeException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orquestração NÃO transacional (D-41): toda chamada HTTP de saída (catalog-service e
 * auth-service) acontece aqui, antes de delegar ao método transacional de outro bean ({@link
 * OrderService#createWithCreditCheck}). {@code com.orderflow.inventory.stock.InventoryService}
 * (inventory-service) documenta em javadoc por que uma chamada de um método para outro do MESMO
 * bean Spring pula o proxy e ignora {@code @Transactional} em silêncio (02-RESEARCH.md Pitfall 2)
 * — é exatamente por isso que a precificação/validação e a decisão transacional vivem em beans
 * diferentes neste serviço.
 *
 * <p>Ordem das recusas de {@link #create}, da mais barata para a mais cara: estrutura do corpo
 * (bean validation, antes deste método ser chamado) → produto repetido no mesmo pedido → itens
 * inexistentes/indisponíveis no catalog-service → total fora da faixa de {@code NUMERIC(19,2)} →
 * limite de crédito (a única checagem que faz uma segunda chamada de rede, e só é alcançada depois
 * que todas as anteriores passaram).
 */
@Service
public class OrderCreationService {

    /**
     * {@code NUMERIC(19,2)} cabe até 17 dígitos na parte inteira — mesmo critério do
     * {@code @Digits(integer = 17, fraction = 2)} de {@code CreateCompanyRequest} no auth-service.
     */
    private static final int MAX_TOTAL_INTEGER_DIGITS = 17;

    private final CatalogServiceClient catalogServiceClient;
    private final AuthServiceClient authServiceClient;
    private final OrderService orderService;

    public OrderCreationService(CatalogServiceClient catalogServiceClient,
                                 AuthServiceClient authServiceClient,
                                 OrderService orderService) {
        this.catalogServiceClient = catalogServiceClient;
        this.authServiceClient = authServiceClient;
        this.orderService = orderService;
    }

    public OrderResponse create(UUID companyId, String createdBy, String bearerToken, CreateOrderRequest request) {
        // Produto repetido é a checagem mais barata (nenhuma rede) e vem antes de qualquer chamada
        // ao catalog-service (D-44) — reforçado pelo acceptance criteria deste plano.
        rejectDuplicateProductIds(request.items());

        List<PricedItem> pricedItems = new ArrayList<>();
        List<UUID> invalidProductIds = new ArrayList<>();

        int lineNumber = 1;
        for (OrderItemRequest itemRequest : request.items()) {
            Optional<CatalogProductResponse> product =
                    catalogServiceClient.findOrderableProduct(itemRequest.productId(), bearerToken);
            if (product.isEmpty()) {
                invalidProductIds.add(itemRequest.productId());
                continue;
            }

            CatalogProductResponse catalogProduct = product.get();
            BigDecimal subtotal = catalogProduct.price().multiply(BigDecimal.valueOf(itemRequest.quantity()));
            pricedItems.add(new PricedItem(
                    lineNumber++,
                    catalogProduct.id(),
                    catalogProduct.sku(),
                    catalogProduct.name(),
                    catalogProduct.price(),
                    itemRequest.quantity(),
                    subtotal));
        }

        // Tudo ou nada (D-44): qualquer item inválido cancela a criação inteira, e isso é
        // verificado ANTES da chamada ao auth-service — nenhuma leitura de limite de crédito
        // acontece para um pedido que já não pode ser criado.
        if (!invalidProductIds.isEmpty()) {
            throw new InvalidOrderItemsException(invalidProductIds);
        }

        // Guarda de total (Task 3): calculado independente do total gravado pelo agregado, mas com
        // a MESMA soma de subtotais — checado ANTES da chamada ao auth-service, a única checagem
        // restante que faz rede (D-44 ordering, javadoc da classe).
        BigDecimal total = BigDecimal.ZERO;
        for (PricedItem pricedItem : pricedItems) {
            total = total.add(pricedItem.subtotal());
        }
        guardTotalFitsInColumn(total);

        BigDecimal creditLimit = authServiceClient.getCreditLimit(companyId, bearerToken);

        return orderService.createWithCreditCheck(companyId, createdBy, pricedItems, creditLimit);
    }

    private void rejectDuplicateProductIds(List<OrderItemRequest> items) {
        Set<UUID> seen = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (!seen.add(item.productId())) {
                throw new DuplicateOrderItemsException();
            }
        }
    }

    /**
     * Mesmo critério do validador de {@code @Digits}: {@code precision() - scale()} é o número de
     * dígitos na parte inteira. Nunca {@code compareTo} contra um limiar numérico — a contagem de
     * dígitos é o que realmente estoura a coluna {@code NUMERIC(19,2)}, não a magnitude do valor
     * (04-RESEARCH.md Common Pitfall 2, mesmo espírito de nunca usar {@code equals} no BigDecimal).
     */
    private void guardTotalFitsInColumn(BigDecimal total) {
        int integerDigits = total.precision() - total.scale();
        if (integerDigits > MAX_TOTAL_INTEGER_DIGITS) {
            throw new OrderTotalOutOfRangeException();
        }
    }
}

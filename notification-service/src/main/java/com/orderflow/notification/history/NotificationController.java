package com.orderflow.notification.history;

import com.orderflow.notification.history.dto.NotificationResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /notifications/{productId}} — lista por Query, nunca item unico (D-31).
 *
 * <p>Restrito a SELLER_ADMIN (Claude's Discretion de {@code 03-CONTEXT.md} — a pesquisa deixou o
 * papel exigido como pergunta aberta e o mapa de padroes recomendava liberar para qualquer
 * autenticado). O historico desta fase e dado operacional do vendedor sobre o proprio catalogo, e
 * negar por padrao e mais barato de relaxar depois do que de apertar: quando a Fase 6 reaproveitar
 * este endpoint para a linha do tempo do pedido, o BUYER vai precisar de acesso restrito aos
 * pedidos da propria empresa — uma regra nova e explicita, nao uma liberacao herdada daqui. Esta
 * escolha diverge deliberadamente da recomendacao da pesquisa.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * {@code GET /notifications/orders/{orderId}} (D-81) — linha do tempo do pedido. Nesta task so o
     * vendedor; a Task 3 abre para o comprador da propria empresa.
     */
    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public List<NotificationResponse> getOrderTimeline(@PathVariable UUID orderId) {
        return notificationService.historyForOrder(orderId);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public List<NotificationResponse> getHistory(@PathVariable UUID productId) {
        return notificationService.history(productId);
    }
}

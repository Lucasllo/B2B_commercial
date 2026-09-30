package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.ReserveStockRequest;
import com.orderflow.inventory.stock.dto.SetStockRequest;
import com.orderflow.inventory.stock.dto.StockResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code PUT /inventory/{productId}} — so o papel SELLER_ADMIN define estoque (INV-01). O ajuste
 * grava o evento {@code STOCK_ADJUSTED} no outbox NA MESMA transacao ({@code
 * InventoryService#setStock}, D-60, 05-04) — o unico caminho de publicacao deste servico e o
 * outbox (relay {@code @Scheduled}), nunca uma chamada direta ao SQS depois do commit; um SQS fora
 * do ar atrasa a entrega em vez de perder o evento (fecha a limitacao de dual-write D-29/D-30 da
 * Fase 3). {@code GET /inventory/{productId}} — qualquer autenticado consulta a disponibilidade
 * exata (D-25), sem restricao de papel alem de {@code authenticated()} vinda de {@code
 * SecurityConfig}. {@code POST .../reservations} e {@code DELETE .../reservations/{reservationId}}
 * — continuam restritos a SELLER_ADMIN como ferramenta administrativa (Open Question 1 da
 * pesquisa: menor churn, nenhum criterio pede remocao; {@code REST_RESERVATION_ENDPOINTS=kept}); a
 * saga da Fase 5 fala com o inventory-service so por SQS (order e inventory nunca por REST entre
 * si), entao a "identidade de servico" prevista antes deixou de ser necessaria. O {@code DELETE}
 * mantem a semantica D-14 (liberar reservationId inexistente e no-op, sem lapide) — a lapide so
 * existe no caminho {@code ReleaseStock} da fila, o unico exposto a entrega fora de ordem da fila
 * padrao (D-66).
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public StockResponse setStock(@PathVariable UUID productId, @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(productId, request.quantityOnHand());
    }

    @GetMapping("/{productId}")
    public StockResponse getStock(@PathVariable UUID productId) {
        return inventoryService.getStock(productId);
    }

    @PostMapping("/{productId}/reservations")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public StockResponse reserve(@PathVariable UUID productId, @Valid @RequestBody ReserveStockRequest request) {
        return inventoryService.reserve(productId, request.reservationId(), request.quantity());
    }

    @DeleteMapping("/{productId}/reservations/{reservationId}")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public StockResponse release(@PathVariable UUID productId, @PathVariable String reservationId) {
        return inventoryService.release(productId, reservationId);
    }
}

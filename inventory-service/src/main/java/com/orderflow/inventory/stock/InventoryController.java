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
 * {@code PUT /inventory/{productId}} — so o papel SELLER_ADMIN define estoque (INV-01).
 * {@code GET /inventory/{productId}} — qualquer autenticado consulta a disponibilidade exata
 * (D-25), sem restricao de papel alem de {@code authenticated()} vinda de {@code SecurityConfig}.
 * {@code POST .../reservations} e {@code DELETE .../reservations/{reservationId}} — restritos a
 * SELLER_ADMIN nesta fase (leitura conservadora: nenhum comprador reserva estoque diretamente; a
 * Fase 5 introduz uma identidade de servico propria para o order-service chamar estas rotas).
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

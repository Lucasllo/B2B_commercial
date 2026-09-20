package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.SetStockRequest;
import com.orderflow.inventory.stock.dto.StockResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code PUT /inventory/{productId}} — so o papel SELLER_ADMIN define estoque (INV-01).
 * {@code GET /inventory/{productId}} — qualquer autenticado consulta a disponibilidade exata
 * (D-25), sem restricao de papel alem de {@code authenticated()} vinda de {@code SecurityConfig}.
 * As rotas de reserva e liberacao entram na Task 3.
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
}

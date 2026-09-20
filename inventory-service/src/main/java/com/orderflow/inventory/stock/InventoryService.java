package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.StockResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Regras de estoque (INV-01, INV-02). Injecao por construtor escrita a mao — sem
 * {@code @Autowired} de campo, mesma convencao de {@code CompanyService}.
 */
@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Upsert (D-18): a primeira chamada para um {@code productId} cria a linha de inventario; as
     * seguintes atualizam a mesma linha. Nenhum metodo deste servico faz chamada HTTP para o
     * catalog-service nem para qualquer outro servico — {@code productId} e referencia opaca
     * (D-15).
     */
    @Transactional
    public StockResponse setStock(UUID productId, int quantityOnHand) {
        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        if (inventory == null) {
            inventory = inventoryRepository.save(new Inventory(productId, quantityOnHand));
        } else {
            inventory.setOnHand(quantityOnHand);
        }
        return StockResponse.from(inventory);
    }

    @Transactional(readOnly = true)
    public StockResponse getStock(UUID productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory not found"));
        return StockResponse.from(inventory);
    }
}

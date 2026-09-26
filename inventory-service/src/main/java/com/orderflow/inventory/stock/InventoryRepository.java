package com.orderflow.inventory.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    /**
     * Leitura em lote usada por {@link InventoryService#reserveAll} — uma consulta para todos os
     * produtos do comando, em vez de uma por linha (D-55).
     */
    List<Inventory> findByProductIdIn(Collection<UUID> productIds);
}

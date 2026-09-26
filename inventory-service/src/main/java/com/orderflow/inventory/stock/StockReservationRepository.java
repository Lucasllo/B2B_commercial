package com.orderflow.inventory.stock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByProductIdAndReservationId(UUID productId, String reservationId);

    /**
     * Leitura em lote usada por {@link InventoryService#reserveAll} — todas as linhas do livro já
     * existentes para este {@code reservationId} entre os produtos do comando, numa única consulta
     * (D-65: distingue reserva nova, replay e livro inconsistente).
     */
    List<StockReservation> findByReservationIdAndProductIdIn(String reservationId, Collection<UUID> productIds);
}

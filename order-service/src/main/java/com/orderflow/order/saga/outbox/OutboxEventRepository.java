package com.orderflow.order.saga.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} do relay (D-59) — mesmo marcador {@code {h-schema}} de
 * {@code CompanyCreditLockRepository} para que o Hibernate prefixe o schema padrão na consulta
 * nativa. {@code @Lock} do JPA não expressa {@code SKIP LOCKED}, por isso nativa. Ordena por
 * {@code attempts} primeiro (OUTBOX_RELAY_DEFAULTS) — um evento que falha sempre (ex.: fila
 * inexistente) nunca ocupa o lote inteiro e deixa eventos novos esperando atrás dele.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM {h-schema}outbox_event "
            + "WHERE published_at IS NULL "
            + "ORDER BY attempts, created_at, id "
            + "LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
}

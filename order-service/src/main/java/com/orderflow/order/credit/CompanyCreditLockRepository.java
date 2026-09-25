package com.orderflow.order.credit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Criação idempotente da linha de trava e {@code SELECT ... FOR UPDATE} (D-40). O marcador
 * {@code {h-schema}} faz o Hibernate prefixar o schema padrão (já entre aspas duplas no
 * application.yml) na consulta nativa — tanto em produção quanto nos testes, onde a URL do
 * Testcontainers não traz {@code currentSchema}.
 */
public interface CompanyCreditLockRepository extends JpaRepository<CompanyCreditLock, UUID> {

    /**
     * Dois pedidos simultâneos de uma empresa nova tentam este INSERT ao mesmo tempo; o perdedor
     * espera o commit do vencedor e vira no-op em vez de erro ({@code ON CONFLICT DO NOTHING}).
     */
    @Modifying
    @Query(value = "INSERT INTO {h-schema}company_credit_lock (company_id) VALUES (:companyId) "
            + "ON CONFLICT (company_id) DO NOTHING", nativeQuery = true)
    void ensureExists(@Param("companyId") UUID companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM CompanyCreditLock l WHERE l.companyId = :companyId")
    Optional<CompanyCreditLock> lockForUpdate(@Param("companyId") UUID companyId);
}

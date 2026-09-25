package com.orderflow.order.credit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Adquire a trava por empresa, sempre dentro de uma transação já aberta (D-40). Propagação
 * {@code MANDATORY} faz qualquer chamada fora de transação falhar alto ({@code
 * IllegalTransactionStateException}) em vez de abrir uma transação nova — uma trava fora de
 * transação seria liberada na hora, e a checagem deixaria de ser serializada.
 *
 * <p>Sem {@code lock_timeout}: a espera é ilimitada de propósito, porque nada que segura esta
 * trava faz I/O de rede (D-41, 04-RESEARCH.md Assumptions A2).
 */
@Component
public class CompanyCreditLocker {

    private final CompanyCreditLockRepository companyCreditLockRepository;

    public CompanyCreditLocker(CompanyCreditLockRepository companyCreditLockRepository) {
        this.companyCreditLockRepository = companyCreditLockRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID companyId) {
        companyCreditLockRepository.ensureExists(companyId);
        companyCreditLockRepository.lockForUpdate(companyId)
                .orElseThrow(() -> new IllegalStateException(
                        "company_credit_lock row must exist after ensureExists for company " + companyId));
    }
}

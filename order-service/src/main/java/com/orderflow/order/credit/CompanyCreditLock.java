package com.orderflow.order.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

/**
 * Mapeia a linha de trava por empresa ({@code company_credit_lock}, D-40). Sem {@code
 * @GeneratedValue}: o id É o próprio {@code companyId} — referência opaca ao auth-service, mesma
 * regra de {@code Order.companyId}.
 */
@Entity
@Table(name = "company_credit_lock")
@Getter
public class CompanyCreditLock {

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    protected CompanyCreditLock() {
    }

    public CompanyCreditLock(UUID companyId) {
        this.companyId = companyId;
    }
}

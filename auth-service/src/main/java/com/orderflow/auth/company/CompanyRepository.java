package com.orderflow.auth.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Nenhuma consulta derivada extra é necessária nesta task — apenas persistência da empresa.
 */
public interface CompanyRepository extends JpaRepository<Company, UUID> {
}

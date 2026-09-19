package com.orderflow.auth.company;

import com.orderflow.auth.company.dto.CompanyResponse;
import com.orderflow.auth.company.dto.CreateCompanyRequest;
import com.orderflow.auth.company.dto.CreditLimitResponse;
import com.orderflow.auth.company.dto.UpdateCreditLimitRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * {@code POST /companies} — só o papel SELLER_ADMIN cria empresas compradoras (AUTH-01, T-01-23).
 * {@code GET}/{@code PUT /companies/{companyId}/credit-limit} — consulta e atualização do limite
 * de crédito com autorização assimétrica: o vendedor lê e escreve; o comprador só lê o da própria
 * empresa (COMP-02, plano 01-05).
 */
@RestController
@RequestMapping("/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        CompanyResponse response = companyService.createCompanyWithBuyer(request);
        return ResponseEntity.created(URI.create("/companies/" + response.id())).body(response);
    }

    @GetMapping("/{companyId}/credit-limit")
    @PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")
    public CreditLimitResponse getCreditLimit(@PathVariable UUID companyId) {
        return companyService.getCreditLimit(companyId);
    }

    @PutMapping("/{companyId}/credit-limit")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    public CreditLimitResponse updateCreditLimit(@PathVariable UUID companyId,
                                                  @Valid @RequestBody UpdateCreditLimitRequest request) {
        return companyService.updateCreditLimit(companyId, request.creditLimit());
    }
}

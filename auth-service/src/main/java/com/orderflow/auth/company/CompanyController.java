package com.orderflow.auth.company;

import com.orderflow.auth.company.dto.CompanyResponse;
import com.orderflow.auth.company.dto.CreateCompanyRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code POST /companies} — só o papel SELLER_ADMIN cria empresas compradoras (AUTH-01, T-01-23).
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
}

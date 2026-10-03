package com.orderflow.auth.company;

import com.orderflow.auth.company.dto.CompanyResponse;
import com.orderflow.auth.company.dto.CreateCompanyRequest;
import com.orderflow.auth.company.dto.CreditLimitResponse;
import com.orderflow.auth.company.dto.UpdateCreditLimitRequest;
import com.orderflow.auth.config.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Empresas", description = "Empresas compradoras e limite de crédito. Criação e alteração do limite exigem SELLER_ADMIN; a consulta aceita o próprio comprador ou o vendedor.")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Criar empresa compradora", description = "Exige o papel SELLER_ADMIN. O usuário vinculado nasce com papel BUYER.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Empresa criada",
                    content = @Content(schema = @Schema(implementation = CompanyResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "E-mail do comprador já utilizado (email_already_used)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        CompanyResponse response = companyService.createCompanyWithBuyer(request);
        return ResponseEntity.created(URI.create("/companies/" + response.id())).body(response);
    }

    @GetMapping("/{companyId}/credit-limit")
    @PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")
    @Operation(summary = "Consultar limite de crédito",
            description = "Exige o próprio comprador ou o vendedor (SELLER_ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Limite vigente",
                    content = @Content(schema = @Schema(implementation = CreditLimitResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Comprador consultando outra empresa (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Empresa inexistente (company_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public CreditLimitResponse getCreditLimit(
            @Parameter(description = "Identificador da empresa compradora", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
            @PathVariable UUID companyId) {
        return companyService.getCreditLimit(companyId);
    }

    @PutMapping("/{companyId}/credit-limit")
    @PreAuthorize("hasRole('SELLER_ADMIN')")
    @Operation(summary = "Atualizar limite de crédito", description = "Exige o papel SELLER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Limite atualizado",
                    content = @Content(schema = @Schema(implementation = CreditLimitResponse.class))),
            @ApiResponse(responseCode = "400", description = "Corpo inválido (validation_failed)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token ausente ou inválido (unauthorized)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Papel diferente de SELLER_ADMIN (forbidden)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Empresa inexistente (company_not_found)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public CreditLimitResponse updateCreditLimit(
            @Parameter(description = "Identificador da empresa compradora", example = "6ba7b810-9dad-11d1-80b4-00c04fd430c8")
            @PathVariable UUID companyId,
            @Valid @RequestBody UpdateCreditLimitRequest request) {
        return companyService.updateCreditLimit(companyId, request.creditLimit());
    }
}

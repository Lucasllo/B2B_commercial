package com.orderflow.auth.company;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mecanismo único de isolamento por empresa desta fase (COMP-03, T-01-31/T-01-33). Consultado via
 * SpEL em {@code @PreAuthorize("@companyGuard.isSelfOrSeller(#companyId)")} — o nome de bean
 * {@code companyGuard} é load-bearing, é por ele que o SpEL resolve este componente.
 *
 * <p>Um filtro global do Hibernate foi deliberadamente descartado como alternativa: um filtro
 * esquecido devolve dados sem escopo com 200 em silêncio, e filtros não cobrem consultas nativas
 * nem escritas. Este guard, ao contrário, é avaliado antes de qualquer acesso ao repositório e
 * falha alto — um 403 comprovado por teste, não uma ausência de filtro que passa despercebida
 * (01-RESEARCH.md Pitfall 6).
 */
@Component("companyGuard")
public class CompanyGuard {

    public boolean isSelfOrSeller(UUID companyId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }

        // Defensivo quanto ao tipo do principal: se não for um Jwt (ex.: contexto de teste
        // fatiado, principal anônimo), devolve false em vez de lançar ClassCastException — uma
        // exceção aqui viraria 500 em vez de 403 (plano 01-05, Task 1).
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return false;
        }

        String role = jwt.getClaimAsString("role");
        if ("SELLER_ADMIN".equals(role)) {
            return true;
        }

        String companyIdClaim = jwt.getClaimAsString("company_id");
        return companyIdClaim != null && companyIdClaim.equals(companyId.toString());
    }
}

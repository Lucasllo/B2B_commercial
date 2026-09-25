package com.orderflow.order.client;

import com.orderflow.order.client.dto.CreditLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code GET /companies/{companyId}/credit-limit} repassando o JWT do próprio BUYER (D-39, D-42 —
 * o {@code companyGuard.isSelfOrSeller} do auth-service já permite). Qualquer resposta que não
 * seja um 200 com um {@code creditLimit} confiável para a empresa pedida vira {@link
 * AuthServiceUnavailableException} — fail-closed, sem cópia local do limite.
 */
@Component
public class AuthServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceClient.class);

    private final RestClient authServiceRestClient;

    public AuthServiceClient(@Qualifier("authServiceRestClient") RestClient authServiceRestClient) {
        this.authServiceRestClient = authServiceRestClient;
    }

    public BigDecimal getCreditLimit(UUID companyId, String bearerToken) {
        CreditLimitResponse response;
        try {
            response = authServiceRestClient.get()
                    .uri("/companies/{companyId}/credit-limit", companyId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(CreditLimitResponse.class);
        } catch (RestClientResponseException e) {
            // Nunca o token nem o corpo da resposta em log — só o nome do vizinho e o status.
            log.warn("auth-service returned unexpected status for credit-limit lookup: {}", e.getStatusCode().value());
            throw new AuthServiceUnavailableException("auth-service unavailable", e);
        } catch (RestClientException e) {
            log.warn("auth-service unavailable for credit-limit lookup: {}", e.getClass().getSimpleName());
            throw new AuthServiceUnavailableException("auth-service unavailable", e);
        }

        if (response == null || response.creditLimit() == null || !companyId.equals(response.companyId())) {
            log.warn("auth-service returned an unreliable credit-limit response");
            throw new AuthServiceUnavailableException("auth-service unavailable");
        }
        return response.creditLimit();
    }
}

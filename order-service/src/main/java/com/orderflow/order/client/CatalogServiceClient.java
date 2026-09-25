package com.orderflow.order.client;

import com.orderflow.order.client.dto.CatalogProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code GET /products/{id}} por item, repassando o JWT do BUYER (D-42). 200 com {@code status}
 * ACTIVE e {@code price} não nulo/negativo devolve o produto; 404 devolve vazio (produto
 * inexistente ou descontinuado são indistinguíveis por desenho para o BUYER, D-24,
 * 04-RESEARCH.md Pitfall 6); qualquer outro status, erro de leitura do corpo, ou {@link
 * RestClientException} vira {@link CatalogServiceUnavailableException}.
 *
 * <p>Usa {@code exchange} (não {@code retrieve}) porque o resultado precisa ramificar em três
 * saídas diferentes por status (produto presente, ausente, indisponível) — {@code retrieve}
 * só permite lançar exceção nos handlers de status, não devolver um valor por ramo.
 */
@Component
public class CatalogServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceClient.class);
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final RestClient catalogServiceRestClient;

    public CatalogServiceClient(@Qualifier("catalogServiceRestClient") RestClient catalogServiceRestClient) {
        this.catalogServiceRestClient = catalogServiceRestClient;
    }

    public Optional<CatalogProductResponse> findOrderableProduct(UUID productId, String bearerToken) {
        try {
            return catalogServiceRestClient.get()
                    .uri("/products/{id}", productId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .exchange((request, response) -> {
                        HttpStatusCode statusCode = response.getStatusCode();
                        if (statusCode.value() == 404) {
                            return Optional.<CatalogProductResponse>empty();
                        }
                        if (!statusCode.is2xxSuccessful()) {
                            log.warn("catalog-service returned unexpected status for product lookup: {}",
                                    statusCode.value());
                            throw new CatalogServiceUnavailableException("catalog-service unavailable");
                        }

                        CatalogProductResponse body = response.bodyTo(CatalogProductResponse.class);
                        if (body == null || body.price() == null || body.price().signum() < 0
                                || !ACTIVE_STATUS.equals(body.status())) {
                            // Defesa em profundidade — o catalog-service já esconde DISCONTINUED
                            // de BUYER (D-24) — tratado igual a "não encontrado", nunca 503.
                            return Optional.<CatalogProductResponse>empty();
                        }
                        return Optional.of(body);
                    });
        } catch (CatalogServiceUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("catalog-service unavailable for product lookup: {}", e.getClass().getSimpleName());
            throw new CatalogServiceUnavailableException("catalog-service unavailable", e);
        }
    }
}

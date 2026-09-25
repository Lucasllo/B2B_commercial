package com.orderflow.order.client;

/**
 * Lançada quando o catalog-service não responde de forma confiável a {@code GET /products/{id}}
 * — status inesperado (nem 200 nem 404), erro de leitura do corpo, ou {@link
 * org.springframework.web.client.RestClientException} (conexão recusada, timeout). Um 404 real
 * (produto inexistente ou descontinuado) NÃO é tratado aqui — vira item inválido de negócio, não
 * uma indisponibilidade técnica (04-RESEARCH.md Pitfall 6).
 */
public class CatalogServiceUnavailableException extends RuntimeException {

    public CatalogServiceUnavailableException(String message) {
        super(message);
    }

    public CatalogServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

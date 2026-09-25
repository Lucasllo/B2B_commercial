package com.orderflow.order.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Propriedades de configuração dos dois clientes de saída (auth-service, catalog-service),
 * ligadas em {@code orderflow.clients.*} no {@code application.yml}. A subida do serviço falha se
 * alguma URL ou timeout faltar — {@code @Validated} + {@code @NotNull} tornam a ausência um erro
 * de startup, não um {@code NullPointerException} na primeira requisição.
 */
@Validated
@ConfigurationProperties(prefix = "orderflow.clients")
public record ClientProperties(
        @NotNull @Valid Downstream authService,
        @NotNull @Valid Downstream catalogService) {

    public record Downstream(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }
}

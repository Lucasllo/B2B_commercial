package com.orderflow.notification.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O esquema de seguranca precisa ser declarado aqui em Java porque o springdoc nao expoe
 * propriedades de {@code info} nem de {@code securitySchemes} no {@code application.yml} — so
 * {@code springdoc.api-docs.*}/{@code springdoc.swagger-ui.*}. Sem o {@link SecurityRequirement}
 * global abaixo, o botao Authorize aparece na Swagger UI mas nenhum endpoint chega a enviar o
 * header {@code Authorization}, e todo Try it out em rota protegida volta 401. Espelha
 * {@code inventory-service}'s {@code OpenApiConfig}, mudando so o pacote.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi(
            @Value("${orderflow.openapi.title}") String title,
            @Value("${orderflow.openapi.description}") String description) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}

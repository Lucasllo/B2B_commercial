package com.orderflow.order.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O esquema de segurança precisa ser declarado aqui em Java porque o springdoc não expõe
 * propriedades de {@code info} nem de {@code securitySchemes} no {@code application.yml} — só
 * {@code springdoc.api-docs.*}/{@code springdoc.swagger-ui.*}. Sem o {@link SecurityRequirement}
 * global abaixo, o botão Authorize aparece na Swagger UI mas nenhum endpoint chega a enviar o
 * header {@code Authorization}, e todo Try it out em rota protegida volta 401.
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

package com.orderflow.inventory.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * O esquema de seguranca precisa ser declarado aqui em Java porque o springdoc nao expoe
 * propriedades de {@code info} nem de {@code securitySchemes} no {@code application.yml} — so
 * {@code springdoc.api-docs.*}/{@code springdoc.swagger-ui.*}. Sem o {@link SecurityRequirement}
 * global abaixo, o botao Authorize aparece na Swagger UI mas nenhum endpoint chega a enviar o
 * header {@code Authorization}, e todo Try it out em rota protegida volta 401.
 *
 * <p>O servidor {@code /api} e o prefixo publico do Gateway. As rotas usam {@code StripPrefix=1},
 * entao {@code /api/inventory} chega aqui como {@code /inventory}. A URL fica relativa
 * ({@code orderflow.openapi.server-url}, default {@code /api}, env {@code ORDERFLOW_OPENAPI_SERVER_URL})
 * para a Swagger UI aberta por {@code 127.0.0.1} nao falhar por CORS; o Try it out pela porta
 * direta do servico deixa de funcionar e a UI canonica e a do Gateway.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi(
            @Value("${orderflow.openapi.title}") String title,
            @Value("${orderflow.openapi.description}") String description,
            @Value("${orderflow.openapi.server-url:/api}") String serverUrl) {
        return new OpenAPI()
                .info(new Info().title(title).description(description).version("1.0.0"))
                .servers(List.of(new Server().url(serverUrl).description("API Gateway")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}

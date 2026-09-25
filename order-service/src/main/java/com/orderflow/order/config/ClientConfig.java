package com.orderflow.order.config;

import com.orderflow.order.config.ClientProperties.Downstream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Constrói os dois {@link RestClient} de saída (auth-service, catalog-service) com timeout
 * explícito de conexão e de leitura — o {@code RestClient} do Spring não tem timeout padrão, e um
 * vizinho travado seguraria a thread para sempre (lição do WR-03 da Fase 3, agora D-41).
 *
 * <p>Não usa {@code ClientHttpRequestFactories} (a fábrica de conveniência citada pela pesquisa da
 * fase): ela está depreciada na linha 3.4+ do Spring Boot. {@link JdkClientHttpRequestFactory} é
 * do próprio spring-web e embrulha o mesmo {@link HttpClient} da JDK que os testes de socket real
 * desta fase também usam.
 */
@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfig {

    @Bean
    public RestClient authServiceRestClient(ClientProperties clientProperties) {
        return buildRestClient(clientProperties.authService());
    }

    @Bean
    public RestClient catalogServiceRestClient(ClientProperties clientProperties) {
        return buildRestClient(clientProperties.catalogService());
    }

    /**
     * Pública (não mais privada, 04-02 Task 2): {@code DownstreamClientsTest} monta o RestClient de
     * produção a partir desta mesma fábrica — com o mesmo {@link JdkClientHttpRequestFactory} e os
     * mesmos timeouts explícitos — em vez de uma cópia, para que o teste de porta fechada exercite
     * exatamente o código que roda em produção, não uma reimplementação que poderia divergir.
     */
    public static RestClient buildRestClient(Downstream downstream) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(downstream.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(downstream.readTimeout());

        return RestClient.builder()
                .baseUrl(downstream.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}

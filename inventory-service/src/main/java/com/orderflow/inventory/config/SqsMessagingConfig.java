package com.orderflow.inventory.config;

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;

/**
 * O contrato entre os servicos e so o JSON do payload — mandar o nome completo de uma classe
 * interna do inventory-service (o atributo {@code JavaType} que o conversor padrao anexa) faz um
 * consumidor padrao tentar carregar essa classe. O notification-service ja se defende disso do
 * lado dele (03-01, {@code SqsMessagingConfig} com {@code payloadTypeMapper} nulo); aqui o
 * produtor para de causar o problema, desligando o envio do atributo na origem.
 *
 * <p>A autoconfiguracao do Spring Cloud AWS 3.4.2 usa este bean, quando existe, no
 * {@code SqsTemplate}, e continua aplicando a ele o {@code ObjectMapper} do Spring Boot (que
 * serializa {@code Instant} como texto ISO-8601).
 */
@Configuration
public class SqsMessagingConfig {

    @Bean
    public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.doNotSendPayloadTypeHeader();
        return converter;
    }

    /**
     * O {@code SqsAsyncClient} padrao (Netty) usa read timeout de 30s e retry standard (3
     * tentativas) — sem limite mais curto, {@code StockEventPublisher.publishStockAdjusted}
     * (chamado sincronamente na thread HTTP do Tomcat, depois do commit) pode prender a
     * requisicao por 1-2 minutos se o LocalStack/SQS aceitar a conexao mas nao responder (WR-03).
     * Limitar a chamada inteira a poucos segundos garante que a resposta HTTP (que ja reflete um
     * commit real no Postgres) nunca fica presa pelo "melhor esforco" declarado do envio.
     */
    @Bean
    public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
        return builder -> builder.overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(3))
                .apiCallAttemptTimeout(Duration.ofSeconds(1)));
    }
}

package com.orderflow.inventory.config;

import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.model.Message;

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
}

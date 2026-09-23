package com.orderflow.notification.config;

import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Por padrao o produtor envia um atributo {@code JavaType} com o nome completo da classe do
 * payload, e o consumidor padrao tenta carregar essa classe — como o DTO do
 * {@code inventory-service} vive noutro pacote e noutro modulo, isso lanca erro de classe
 * inexistente antes do listener rodar, a mensagem nunca e confirmada, e sem fila de mensagens
 * mortas ela volta para sempre. O consumidor nao pode depender de o produtor desligar esse
 * atributo — quem decide a conversao e o tipo do parametro do metodo do listener, nunca o
 * atributo enviado pelo produtor.
 *
 * <p>A autoconfiguracao do Spring Cloud AWS 3.4.2 usa um bean deste tipo, quando existe, tanto no
 * {@code SqsTemplate} quanto na fabrica padrao de containers de listener, e continua aplicando o
 * {@code ObjectMapper} do Spring Boot a ele.
 */
@Configuration
public class SqsMessagingConfig {

    @Bean
    public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.setPayloadTypeMapper(message -> null);
        return converter;
    }
}

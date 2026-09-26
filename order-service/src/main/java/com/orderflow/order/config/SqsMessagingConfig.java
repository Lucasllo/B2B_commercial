package com.orderflow.order.config;

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;

/**
 * Configuração SQS do order-service — a partir desta fase o serviço produz (relay do outbox) e
 * consome (05-03, resultado da reserva), então precisa das duas metades do conversor de payload já
 * provadas em inventory-service/notification-service: {@code doNotSendPayloadTypeHeader()} evita
 * que o produtor anexe o atributo {@code JavaType} (o contrato entre os serviços é só o JSON do
 * payload, nunca o nome de uma classe interna deste módulo); {@code setPayloadTypeMapper(message ->
 * null)} faz o consumidor decidir o tipo pelo parâmetro do método do listener, nunca por esse
 * atributo enviado pelo produtor.
 *
 * <p>O mesmo timeout curto de {@code SqsAsyncClient} de inventory-service: sem ele, o relay
 * {@code @Scheduled} poderia ficar preso minutos numa chamada que o LocalStack aceitou mas não
 * respondeu.
 *
 * <p>[Rule 1 - Bug, 05-03]: 3s/1s (Fase 3/4, D-41) foi pensado só para a chamada síncrona do
 * {@code RestClient}. O primeiro {@code @SqsListener} real deste serviço ({@code
 * ReservationResultListener}, 05-03) some com {@code poll-timeout=0} (application.yml), mas sob a
 * contenção de dez requisições concorrentes de teste o teto de 1s por tentativa ainda estourava
 * esporadicamente — a mesma folga de 5s/2s já aplicada ao inventory-service em 05-02 resolve sem
 * afrouxar o motivo original (evitar prender a thread HTTP para sempre).
 */
@Configuration
public class SqsMessagingConfig {

    @Bean
    public MessagingMessageConverter<Message> sqsMessagingMessageConverter() {
        SqsMessagingMessageConverter converter = new SqsMessagingMessageConverter();
        converter.doNotSendPayloadTypeHeader();
        converter.setPayloadTypeMapper(message -> null);
        return converter;
    }

    @Bean
    public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
        return builder -> builder.overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(2)));
    }
}

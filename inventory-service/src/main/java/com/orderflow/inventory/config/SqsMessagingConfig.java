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
 *
 * <p>Fase 5 (D-61): este servico passa a tambem CONSUMIR mensagens ({@code
 * ReservationCommandListener}, {@code @SqsListener(String payload)}) — {@code
 * setPayloadTypeMapper(message -> null)} evita que o conversor padrao tente resolver um tipo Java a
 * partir do atributo de mensagem (ausente, ja que o produtor do outro lado tambem desliga o envio
 * do atributo), mesmo motivo/tecnica ja usada pelo notification-service (Fase 3).
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

    /**
     * O {@code SqsAsyncClient} padrao (Netty) usa read timeout de 30s e retry standard (3
     * tentativas) — sem limite mais curto, uma chamada sincrona ao SQS na thread HTTP do Tomcat
     * poderia prender a requisicao por 1-2 minutos se o LocalStack/SQS aceitar a conexao mas nao
     * responder (WR-03). A partir da Fase 5 (D-60, 05-04) {@code PUT /inventory/{productId}} nao
     * fala mais com o SQS diretamente — o {@code STOCK_ADJUSTED} vai para o outbox na mesma
     * transacao e quem fala com o SQS e o relay {@code @Scheduled} ({@code OutboxRelay}), fora da
     * thread HTTP. Este limite curto agora protege justamente o relay: ele segura linhas do outbox
     * (nao commitadas ao SQS ainda) durante o envio, e um SQS lento nao pode prender esse ciclo por
     * mais que alguns segundos, sob risco de atrasar o proximo lote.
     *
     * <p>Fase 5 (D-61): este {@code SqsAsyncClient} tambem e compartilhado pelo container do
     * {@code ReservationCommandListener} (as dez requisicoes {@code ReceiveMessage} concorrentes do
     * padrao do Spring Cloud AWS competem pelo mesmo pool de conexoes Netty que o relay e o
     * listener). {@code orderflow.messaging}/{@code spring.cloud.aws.sqs.listener.poll-timeout=0s}
     * (application.yml) desliga o long polling do listener para que cada {@code ReceiveMessage}
     * responda na hora em vez de segurar a conexao por ate 20s — sem isso, toda tentativa de
     * recebimento estouraria {@code apiCallAttemptTimeout} sozinha. Os valores abaixo ganharam
     * folga (de 1s/3s para 2s/5s) para absorver a contencao dessas dez requisicoes concorrentes.
     */
    @Bean
    public SqsAsyncClientCustomizer sqsAsyncClientTimeoutCustomizer() {
        return builder -> builder.overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(2)));
    }
}

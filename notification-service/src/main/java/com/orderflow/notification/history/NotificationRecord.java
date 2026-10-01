package com.orderflow.notification.history;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * Item da tabela DynamoDB {@code notification-history}. Classe publica (nao {@code record}) com
 * Lombok {@code @Getter}/{@code @Setter}/{@code @NoArgsConstructor} publicos — o Enhanced Client
 * exige construtor sem argumentos e pares getter/setter publicos, deliberadamente diferente do
 * estilo de entidade JPA de {@code Inventory} ({@code record} do Java tem campos implicitamente
 * finais, incompativel com {@code @DynamoDbBean}; 03-RESEARCH.md §Anti-Patterns).
 *
 * <p>Os nomes {@code entityId}/{@code sortKey} sao o key-schema criado pelo init hook do
 * LocalStack ({@code localstack-init/ready.d/01-create-notification-resources.sh}) — precisam
 * bater exatamente. A particao e generica (D-80, realiza D-32): produto nos eventos STOCK_ADJUSTED,
 * pedido nos eventos ORDER_*. {@code companyId} so existe nos itens de pedido (nulo nos de produto) e
 * serve a regra de leitura do comprador (D-81). O formato da sort key e {@code eventType#eventId}
 * (D-33): distingue reentrega
 * da mesma mensagem (mesmo eventId, sobrescreve) de um evento novo do mesmo tipo (eventId
 * diferente, nova linha).
 */
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class NotificationRecord {

    private String entityId;
    private String companyId;
    private String sortKey;
    private String eventId;
    private String eventType;
    private String message;
    private String rawPayload;
    private Instant occurredAt;
    private Instant recordedAt;

    @DynamoDbPartitionKey
    public String getEntityId() {
        return entityId;
    }

    @DynamoDbSortKey
    public String getSortKey() {
        return sortKey;
    }
}

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
 * <p>Os nomes {@code productId}/{@code sortKey} sao o key-schema criado pelo init hook do
 * LocalStack ({@code localstack-init/ready.d/01-create-notification-resources.sh}) — precisam
 * bater exatamente. O formato da sort key e {@code eventType#eventId} (D-33): distingue reentrega
 * da mesma mensagem (mesmo eventId, sobrescreve) de um evento novo do mesmo tipo (eventId
 * diferente, nova linha).
 */
@Getter
@Setter
@NoArgsConstructor
@DynamoDbBean
public class NotificationRecord {

    private String productId;
    private String sortKey;
    private String eventId;
    private String eventType;
    private String message;
    private String rawPayload;
    private Instant occurredAt;
    private Instant recordedAt;

    @DynamoDbPartitionKey
    public String getProductId() {
        return productId;
    }

    @DynamoDbSortKey
    public String getSortKey() {
        return sortKey;
    }
}

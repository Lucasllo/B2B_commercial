package com.orderflow.notification.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;

/**
 * Usa {@link DynamoDbEnhancedClient} diretamente, nao o {@code DynamoDbTemplate} do starter: a
 * assinatura de consulta do template nao foi confirmada pela pesquisa (03-RESEARCH.md Assumptions
 * Log A2) e o resolvedor de nome de tabela padrao dele derivaria um nome a partir da classe em
 * vez de usar o nome explicito da configuracao (Claude's Discretion de 03-CONTEXT.md).
 */
@Repository
public class NotificationRepository {

    private final DynamoDbTable<NotificationRecord> table;

    public NotificationRepository(DynamoDbEnhancedClient dynamoDbEnhancedClient,
                                   @Value("${orderflow.notifications.table-name}") String tableName) {
        this.table = dynamoDbEnhancedClient.table(tableName, TableSchema.fromBean(NotificationRecord.class));
    }

    /**
     * {@code putItem} sem nenhuma expressao de condicao — e exatamente essa semantica
     * (substituicao completa do item de mesma chave) que da a sobrescrita em reentrega de graca
     * (03-RESEARCH.md §Don't Hand-Roll). Nao faz leitura previa do item antes de gravar.
     */
    public void save(NotificationRecord record) {
        table.putItem(record);
    }

    /**
     * Query na partition key — devolve todos os itens de todas as paginas.
     */
    public List<NotificationRecord> findByProductId(String productId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(productId).build());
        return table.query(condition).items().stream().toList();
    }
}

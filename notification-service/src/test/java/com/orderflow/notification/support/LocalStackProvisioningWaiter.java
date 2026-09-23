package com.orderflow.notification.support;

import org.awaitility.Awaitility;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

/**
 * Classe separada de {@link LocalStackTestSupport} de proposito. Awaitility avalia o lambda de
 * espera numa thread de background propria, nao na thread que executa o bloco {@code static} de
 * {@code LocalStackTestSupport}; se o lambda fosse um metodo sintetico DAQUELA classe (por estar
 * escrito dentro dela), invocar esse metodo a partir da thread de background do Awaitility
 * dispararia a checagem de inicializacao de classe da JVM (JLS 12.4.1) para
 * {@code LocalStackTestSupport} — que ainda esta em {@code <clinit>} na thread principal — e essa
 * thread de background ficaria bloqueada esperando a inicializacao terminar, enquanto a thread
 * principal esta bloqueada esperando o resultado da thread de background: deadlock de
 * inicializacao de classe. Isolar o lambda nesta classe, ja totalmente inicializada antes de o
 * lambda rodar, evita o ciclo.
 */
final class LocalStackProvisioningWaiter {

    private LocalStackProvisioningWaiter() {
    }

    static void await(LocalStackContainer container, String region, String queueName, String tableName) {
        SqsClient sqsClient = SqsClient.builder()
                .endpointOverride(container.getEndpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .build();
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(container.getEndpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .build();

        try {
            Awaitility.await("init hook do LocalStack criar a fila e a tabela")
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        sqsClient.getQueueUrl(r -> r.queueName(queueName));
                        TableStatus status = dynamoDbClient.describeTable(r -> r.tableName(tableName))
                                .table().tableStatus();
                        if (status != TableStatus.ACTIVE) {
                            throw new IllegalStateException("Table not ACTIVE yet: " + status);
                        }
                    });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new IllegalStateException(
                    "A fila '" + queueName + "' e/ou a tabela '" + tableName + "' nao ficaram "
                            + "prontas em 60s — verifique o init hook "
                            + "localstack-init/ready.d/01-create-notification-resources.sh", e);
        } finally {
            sqsClient.close();
            dynamoDbClient.close();
        }
    }
}

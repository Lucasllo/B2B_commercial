package com.orderflow.inventory.support;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
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
 * inicializacao de classe (mesmo achado registrado em 03-01-SUMMARY.md). Isolar o lambda nesta
 * classe, ja totalmente inicializada antes de o lambda rodar, evita o ciclo.
 *
 * <p>Ao contrario do equivalente do {@code notification-service}, so espera pela fila — este
 * modulo nao tem o cliente do DynamoDB no classpath.
 */
final class LocalStackProvisioningWaiter {

    private LocalStackProvisioningWaiter() {
    }

    static void awaitProvisioned(LocalStackContainer container, String region, String queueName) {
        SqsClient sqsClient = SqsClient.builder()
                .endpointOverride(container.getEndpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .build();

        try {
            Awaitility.await("init hook do LocalStack criar a fila")
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> sqsClient.getQueueUrl(r -> r.queueName(queueName)));
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException(
                    "A fila '" + queueName + "' nao ficou pronta em 60s — verifique o init hook "
                            + "localstack-init/ready.d/01-create-notification-resources.sh", e);
        } finally {
            sqsClient.close();
        }
    }
}

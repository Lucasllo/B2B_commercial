package com.orderflow.inventory.support;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.util.List;

/**
 * Classe separada de {@link LocalStackTestSupport} de proposito. Awaitility avalia o lambda de
 * espera numa thread de background propria, nao na thread que executa o bloco {@code static} de
 * {@code LocalStackTestSupport}; se o lambda fosse um metodo sintetico DAQUELA classe (por estar
 * escrito dentro dela), invocar esse metodo a partir da thread de background do Awaitility
 * dispararia a checagem de inicializacao de classe da JVM (JLS 12.4.1) para
 * {@code LocalStackTestSupport} — que ainda esta em {@code <clinit>} na thread principal — e essa
 * thread de background ficaria bloqueada esperando a inicializacao terminar, enquanto a thread
 * principal esta bloqueada esperando o resultado da thread de background: deadlock de
 * inicializacao de classe (mesmo achado registrado em 03-01-SUMMARY.md).
 *
 * <p>Fase 5 (05-02): passa a receber uma {@link List} de filas — mesma forma do equivalente do
 * order-service — porque este servico agora espera pela fila de notificacao (Fase 3) e pelas duas
 * filas da saga (Fase 5), criadas por hooks de init diferentes.
 */
final class LocalStackProvisioningWaiter {

    private LocalStackProvisioningWaiter() {
    }

    static void awaitProvisioned(LocalStackContainer container, String region, List<String> queueNames) {
        SqsClient sqsClient = SqsClient.builder()
                .endpointOverride(container.getEndpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
                .build();

        try {
            Awaitility.await("init hooks do LocalStack criarem as filas")
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        for (String queueName : queueNames) {
                            sqsClient.getQueueUrl(r -> r.queueName(queueName));
                        }
                    });
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException(
                    "As filas " + queueNames + " nao ficaram prontas em 60s — verifique os init hooks "
                            + "em localstack-init/ready.d/", e);
        } finally {
            sqsClient.close();
        }
    }
}

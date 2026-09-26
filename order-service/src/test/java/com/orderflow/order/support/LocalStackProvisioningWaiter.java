package com.orderflow.order.support;

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
 * Classe separada de {@link LocalStackTestSupport} de propósito — mesmo motivo do equivalente em
 * inventory-service: Awaitility avalia o lambda de espera numa thread de background própria; se o
 * lambda fosse um método sintético de {@code LocalStackTestSupport} (por estar escrito dentro
 * dela), invocá-lo a partir dessa thread de background disparia a checagem de inicialização de
 * classe da JVM (JLS 12.4.1) para {@code LocalStackTestSupport} — ainda em {@code <clinit>} na
 * thread principal — e a thread de background ficaria bloqueada esperando essa inicialização
 * terminar, enquanto a thread principal está bloqueada esperando o resultado da thread de
 * background: deadlock de inicialização de classe. Isolar o lambda nesta classe, já totalmente
 * inicializada antes de o lambda rodar, evita o ciclo.
 *
 * <p>Ao contrário do equivalente do inventory-service (uma fila), este método espera por VÁRIAS
 * filas — as duas da saga ({@code inventory-commands-queue} e {@code order-events-queue}).
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
            Awaitility.await("init hook do LocalStack criar as filas da saga")
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        for (String queueName : queueNames) {
                            sqsClient.getQueueUrl(r -> r.queueName(queueName));
                        }
                    });
        } catch (ConditionTimeoutException e) {
            throw new IllegalStateException(
                    "As filas " + queueNames + " não ficaram prontas em 60s — verifique o init hook "
                            + "localstack-init/ready.d/02-create-order-saga-resources.sh", e);
        } finally {
            sqsClient.close();
        }
    }
}

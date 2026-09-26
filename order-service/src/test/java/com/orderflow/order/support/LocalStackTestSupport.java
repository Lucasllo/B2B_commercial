package com.orderflow.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Suporte de teste com um container {@link LocalStackContainer} singleton — cópia do suporte
 * equivalente do {@code inventory-service} (05-RESEARCH.md). Só o serviço SQS: este módulo não usa
 * DynamoDB. Espera pelas DUAS filas criadas pelo init hook da saga ({@code
 * inventory-commands-queue} e {@code order-events-queue}) — {@link
 * OrderTestInfrastructure#register} chama {@link #registerAwsProperties} para que toda classe de
 * IT do módulo suba com o mesmo LocalStack singleton.
 */
public final class LocalStackTestSupport {

    private static final String REGION = "us-east-1";
    private static final List<String> QUEUE_NAMES = List.of("inventory-commands-queue", "order-events-queue");

    // Mesma tag de imagem que o docker-compose.yml e os demais serviços usam. O init hook copiado
    // abaixo é o mesmo arquivo usado pelo compose — o teste prova o provisionamento real, nunca
    // uma fila criada pelo próprio teste.
    public static final LocalStackContainer CONTAINER = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:2026.08.3"))
            .withServices(Service.SQS)
            .withEnv("LOCALSTACK_AUTH_TOKEN", resolveAuthToken())
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initHookPath(), 0755),
                    "/etc/localstack/init/ready.d/02-create-order-saga-resources.sh");

    static {
        CONTAINER.start();
        LocalStackProvisioningWaiter.awaitProvisioned(CONTAINER, REGION, QUEUE_NAMES);
    }

    private LocalStackTestSupport() {
    }

    private static String initHookPath() {
        // Resolvido a partir do diretório do módulo (order-service) subindo um nível até a raiz do
        // repositório, onde vive localstack-init/.
        return Path.of("..", "localstack-init", "ready.d", "02-create-order-saga-resources.sh")
                .toAbsolutePath().normalize().toString();
    }

    /**
     * Mesma regra do inventory-service: por padrão usa {@code
     * System.getenv("LOCALSTACK_AUTH_TOKEN")} se não estiver em branco; senão sobe do diretório de
     * trabalho atual pelos diretórios pais procurando um arquivo {@code .env} e usa o valor da
     * linha {@code LOCALSTACK_AUTH_TOKEN=} (sem aspas em volta) se não estiver em branco; senão
     * lança {@link IllegalStateException}. O valor do token nunca aparece em log, em mensagem de
     * exceção nem em saída de teste — a mensagem de erro nomeia apenas a variável.
     */
    static String resolveAuthToken() {
        String fromEnv = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }

        String fromDotEnv = readFromDotEnvUpwards();
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv;
        }

        throw new IllegalStateException(
                "LOCALSTACK_AUTH_TOKEN nao encontrado. Exporte a variavel de ambiente "
                        + "LOCALSTACK_AUTH_TOKEN ou preencha a linha LOCALSTACK_AUTH_TOKEN= no "
                        + "arquivo .env da raiz do repositorio.");
    }

    private static String readFromDotEnvUpwards() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                String value = readTokenLine(candidate);
                if (value != null) {
                    return value;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String readTokenLine(Path envFile) {
        try (BufferedReader reader = Files.newBufferedReader(envFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("LOCALSTACK_AUTH_TOKEN=")) {
                    return line.substring("LOCALSTACK_AUTH_TOKEN=".length()).trim();
                }
            }
        } catch (IOException e) {
            // Arquivo ilegível — trata como ausente, sem propagar o caminho nem o motivo em log.
            return null;
        }
        return null;
    }

    public static void registerAwsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.region.static", () -> REGION);
        registry.add("spring.cloud.aws.credentials.access-key", CONTAINER::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", CONTAINER::getSecretKey);
        registry.add("spring.cloud.aws.endpoint", () -> CONTAINER.getEndpoint().toString());
    }
}

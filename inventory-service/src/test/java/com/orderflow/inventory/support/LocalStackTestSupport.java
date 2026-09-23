package com.orderflow.inventory.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Suporte de teste com um container {@link LocalStackContainer} singleton, o token resolvido sem
 * nunca ser impresso, e as propriedades {@code spring.cloud.aws.*} registradas a mao — Spring
 * Cloud AWS nao oferece conexao de servico automatica para o LocalStack (03-RESEARCH.md
 * Pitfall A). Copia do suporte equivalente do {@code notification-service} (03-01); a unica
 * diferenca e que a espera de provisionamento aqui cobre so a fila {@code notification-events-queue}
 * — este modulo nao tem o cliente do DynamoDB no classpath. O container e compartilhado por todas
 * as classes de teste da JVM (mesmo padrao singleton de {@code AbstractIntegrationTest}).
 *
 * <p>A espera pelo provisionamento fica isolada em {@link LocalStackProvisioningWaiter} — ver o
 * javadoc daquela classe para o motivo (deadlock de inicializacao de classe da JVM entre esta
 * thread, que executa o bloco {@code static}, e a thread de background do Awaitility).
 */
public final class LocalStackTestSupport {

    private static final String REGION = "us-east-1";
    private static final String QUEUE_NAME = "notification-events-queue";

    // Mesma tag de imagem que o docker-compose.yml usa. O init hook copiado abaixo e o mesmo
    // arquivo usado pelo compose e pelo notification-service — o teste prova o provisionamento
    // real, nao uma fila criada pelo proprio teste.
    public static final LocalStackContainer CONTAINER = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:2026.08.3"))
            .withServices(Service.SQS, Service.DYNAMODB)
            .withEnv("LOCALSTACK_AUTH_TOKEN", resolveAuthToken())
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initHookPath(), 0755),
                    "/etc/localstack/init/ready.d/01-create-notification-resources.sh");

    static {
        CONTAINER.start();
        LocalStackProvisioningWaiter.awaitProvisioned(CONTAINER, REGION, QUEUE_NAME);
    }

    private LocalStackTestSupport() {
    }

    private static String initHookPath() {
        // Resolvido a partir do diretorio do modulo (inventory-service) subindo um nivel ate a
        // raiz do repositorio, onde vive localstack-init/.
        return Path.of("..", "localstack-init", "ready.d", "01-create-notification-resources.sh")
                .toAbsolutePath().normalize().toString();
    }

    /**
     * Explicando o porque: por padrao usa {@code System.getenv("LOCALSTACK_AUTH_TOKEN")} se nao
     * estiver em branco; senao sobe do diretorio de trabalho atual pelos diretorios pais
     * procurando um arquivo {@code .env} e usa o valor da linha {@code LOCALSTACK_AUTH_TOKEN=}
     * (sem aspas em volta) se nao estiver em branco; senao lanca {@link IllegalStateException}. O
     * valor do token nunca aparece em log, em mensagem de excecao nem em saida de teste — a
     * mensagem de erro nomeia apenas a variavel.
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
            // Arquivo ilegivel — trata como ausente, sem propagar o caminho nem o motivo em log.
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

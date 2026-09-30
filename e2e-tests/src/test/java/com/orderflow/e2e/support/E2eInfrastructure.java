package com.orderflow.e2e.support;

import com.orderflow.inventory.InventoryServiceApplication;
import com.orderflow.order.OrderServiceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Infraestrutura singleton do módulo E2E (D-68, {@code E2E_CONFIG_STRATEGY}) — inicialização num
 * bloco {@code static}, mesmo padrão singleton de {@code OrderTestInfrastructure}/{@code
 * AbstractIntegrationTest} dos demais módulos: um único {@code start()} por container, nunca
 * interrompido entre classes de teste, mesmo com {@code E2eContextsSmokeIT} e {@code
 * OrderReservationSagaE2EIT} na mesma suíte.
 *
 * <p>Sobe, nesta ordem: {@link #POSTGRES} (container real, uma instância, um schema por serviço —
 * mesmo desenho do {@code docker-compose.yml}); {@link #STUB_SERVER} (auth-service/catalog-service
 * stubados, mesmo padrão de {@code DownstreamStubServer} da Fase 4); {@link
 * LocalStackTestSupport#CONTAINER} (disparado por referência estática, container real + espera
 * pelas filas da saga); e por fim os DOIS contextos Spring reais — {@link #INVENTORY_CONTEXT}
 * primeiro, depois {@link #ORDER_CONTEXT} — cada um via {@link SpringApplicationBuilder}, nunca
 * {@code @SpringBootTest} (não existe {@code TestContext} do Spring aqui: são duas aplicações
 * completas, independentes, no mesmo processo).
 *
 * <p><b>Risco de colisão de configuração (05-RESEARCH.md Pitfall 2):</b> os dois jars
 * (order-service, inventory-service) trazem cada um seu próprio {@code application.yml} na raiz do
 * classpath deste módulo — {@code ClassLoader.getResource("application.yml")} devolveria só o
 * PRIMEIRO encontrado (não uma mescla), então nenhum contexto pode depender da localização padrão
 * {@code classpath:/}. Cada contexto recebe {@code --spring.config.location} apontando para o
 * ARQUIVO REAL do seu próprio serviço (nunca uma cópia, que poderia divergir), {@code
 * --spring.config.additional-location} para os overrides deste módulo (porta 0, {@code
 * spring.flyway.locations=filesystem:...} — apontar direto para a pasta de migrações do próprio
 * serviço no disco, nunca `classpath:db/migration`, que veria as migrações dos DOIS serviços e
 * falharia na versão 1 duplicada), e por fim os valores dinâmicos (URLs de containers e do stub)
 * como argumento de linha de comando, que tem precedência máxima sobre qualquer arquivo de
 * configuração — a resolução ambígua de {@code classpath:} nunca entra em jogo. {@link
 * com.orderflow.e2e.E2eContextsSmokeIT} prova essa mitigação antes de qualquer teste da saga.
 */
public final class E2eInfrastructure {

    private static final String REGION = "us-east-1";

    // Mesma tag de imagem Postgres que o docker-compose.yml e os demais módulos usam.
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15");
    public static final DownstreamStubServer STUB_SERVER = new DownstreamStubServer();

    public static final ConfigurableApplicationContext INVENTORY_CONTEXT;
    public static final ConfigurableApplicationContext ORDER_CONTEXT;

    private static final int INVENTORY_PORT;
    private static final int ORDER_PORT;

    private static final JdbcTemplate JDBC_TEMPLATE;

    static {
        POSTGRES.start();
        // Referenciar CONTAINER dispara o <clinit> de LocalStackTestSupport (container real +
        // espera pelas filas da saga) ANTES de qualquer contexto subir — os dois serviços usam
        // queue-not-found-strategy=fail, então um @SqsListener falharia ao subir contra uma fila
        // ainda inexistente.
        LocalStackTestSupport.CONTAINER.getEndpoint();

        JDBC_TEMPLATE = new JdbcTemplate(readOnlyDataSource());

        AtomicInteger inventoryPortHolder = new AtomicInteger();
        INVENTORY_CONTEXT = startInventoryContext(inventoryPortHolder);
        INVENTORY_PORT = inventoryPortHolder.get();

        AtomicInteger orderPortHolder = new AtomicInteger();
        ORDER_CONTEXT = startOrderContext(orderPortHolder);
        ORDER_PORT = orderPortHolder.get();
    }

    private E2eInfrastructure() {
    }

    public static String orderBaseUrl() {
        return "http://127.0.0.1:" + ORDER_PORT;
    }

    public static String inventoryBaseUrl() {
        return "http://127.0.0.1:" + INVENTORY_PORT;
    }

    /** Só leitura nos testes (TEST-03 — nenhuma escrita direta no banco de pedidos/estoque). */
    public static JdbcTemplate jdbc() {
        return JDBC_TEMPLATE;
    }

    public static DownstreamStubServer stub() {
        return STUB_SERVER;
    }

    /** {@code SqsAsyncClient} do contexto do inventory-service — usado para republicar comandos. */
    public static SqsAsyncClient inventorySqs() {
        return INVENTORY_CONTEXT.getBean(SqsAsyncClient.class);
    }

    private static ConfigurableApplicationContext startInventoryContext(AtomicInteger portHolder) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(InventoryServiceApplication.class)
                .initializers(context -> context.addApplicationListener(portListener(portHolder)));
        return builder.run(
                "--spring.config.location=file:../inventory-service/src/main/resources/application.yml",
                "--spring.config.additional-location=classpath:/e2e/inventory-service-overrides.yml",
                "--spring.datasource.url=" + jdbcUrlWithSchema("inventory"),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.cloud.aws.endpoint=" + LocalStackTestSupport.CONTAINER.getEndpoint(),
                "--spring.cloud.aws.region.static=" + REGION,
                "--spring.cloud.aws.credentials.access-key=" + LocalStackTestSupport.CONTAINER.getAccessKey(),
                "--spring.cloud.aws.credentials.secret-key=" + LocalStackTestSupport.CONTAINER.getSecretKey(),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + STUB_SERVER.baseUrl()
                        + "/.well-known/jwks.json");
    }

    private static ConfigurableApplicationContext startOrderContext(AtomicInteger portHolder) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(OrderServiceApplication.class)
                .initializers(context -> context.addApplicationListener(portListener(portHolder)));
        return builder.run(
                "--spring.config.location=file:../order-service/src/main/resources/application.yml",
                "--spring.config.additional-location=classpath:/e2e/order-service-overrides.yml",
                "--spring.datasource.url=" + jdbcUrlWithSchema("order"),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.cloud.aws.endpoint=" + LocalStackTestSupport.CONTAINER.getEndpoint(),
                "--spring.cloud.aws.region.static=" + REGION,
                "--spring.cloud.aws.credentials.access-key=" + LocalStackTestSupport.CONTAINER.getAccessKey(),
                "--spring.cloud.aws.credentials.secret-key=" + LocalStackTestSupport.CONTAINER.getSecretKey(),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + STUB_SERVER.baseUrl()
                        + "/.well-known/jwks.json",
                "--orderflow.clients.auth-service.base-url=" + STUB_SERVER.baseUrl(),
                "--orderflow.clients.catalog-service.base-url=" + STUB_SERVER.baseUrl());
    }

    /**
     * Nenhuma versão deste projeto expõe {@code local.server.port} fora do {@code TestContext} do
     * Spring (isso é amarrado a {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}, que não se
     * aplica aqui — as duas aplicações sobem via {@link SpringApplicationBuilder} puro). Capturar a
     * porta efetiva pelo próprio evento que o servidor embutido publica ao subir é a forma correta e
     * estável entre versões do Boot.
     */
    private static ApplicationListener<WebServerInitializedEvent> portListener(AtomicInteger portHolder) {
        return event -> portHolder.set(event.getWebServer().getPort());
    }

    /**
     * Junta o parâmetro {@code currentSchema} à URL JDBC do container, respeitando se a URL
     * devolvida pelo Testcontainers já contém uma {@code ?} própria.
     */
    private static String jdbcUrlWithSchema(String schema) {
        String baseUrl = POSTGRES.getJdbcUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "currentSchema=" + schema;
    }

    private static DataSource readOnlyDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

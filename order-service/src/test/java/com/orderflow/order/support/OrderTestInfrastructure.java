package com.orderflow.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Container Postgres e stub HTTP compartilhados por toda a JVM de teste (padrão singleton, mesmo
 * motivo documentado em {@code AbstractIntegrationTest} do catalog-service): iniciar num bloco
 * {@code static} garante um único {@code start()}, nunca interrompido entre classes de teste,
 * mesmo quando classes de socket real (Task 2) e classes com {@code MockMvc} (Task 1) coexistem
 * na mesma suíte. {@link #register} substitui {@code @ServiceConnection} por registro manual —
 * assim as duas famílias de teste compartilham exatamente o mesmo container e o mesmo stub.
 *
 * <p>A partir da Fase 5, {@link #register} também chama {@link
 * LocalStackTestSupport#registerAwsProperties} — toda classe de IT do módulo (base e as de socket
 * real) sobe com o mesmo LocalStack singleton, exatamente como as classes de Postgres/stub acima.
 */
public final class OrderTestInfrastructure {

    // Mesma tag de imagem Postgres que o docker-compose.yml e os demais serviços usam.
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15");
    public static final DownstreamStubServer STUB_SERVER = new DownstreamStubServer();

    static {
        POSTGRES.start();
    }

    private OrderTestInfrastructure() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("orderflow.clients.auth-service.base-url", STUB_SERVER::baseUrl);
        registry.add("orderflow.clients.catalog-service.base-url", STUB_SERVER::baseUrl);
        LocalStackTestSupport.registerAwsProperties(registry);
    }
}

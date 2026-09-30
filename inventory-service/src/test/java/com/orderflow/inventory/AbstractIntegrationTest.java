package com.orderflow.inventory;

import com.orderflow.inventory.support.LocalStackTestSupport;
import com.orderflow.inventory.support.TestJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Classe base de integracao — sobe um PostgreSQL real via Testcontainers e reutiliza o mesmo
 * contexto Spring para toda a suite. O container e {@code static} de proposito.
 *
 * <p><b>Padrao "singleton container" (Testcontainers docs), nao {@code @Testcontainers}/
 * {@code @Container}</b>: com duas ou mais classes {@code *IT} estendendo esta base, a extensao
 * JUnit 5 {@code @Testcontainers} para um campo {@code static} chamaria {@code stop()} desse
 * container no {@code afterAll} de CADA classe de teste — como o campo e herdado e compartilhado,
 * a primeira classe a terminar derrubaria o container no meio da suite, e a classe seguinte
 * recriaria um container novo (ID e porta diferentes) enquanto o pool de conexoes do Hikari ainda
 * apontava para a porta antiga — produzindo {@code Connection refused} e erros aleatorios em
 * qualquer teste subsequente. Iniciar o container manualmente num bloco {@code static} (sem
 * {@code @Testcontainers}) garante um unico {@code start()} por JVM, nunca interrompido entre
 * classes; a limpeza fica a cargo do Ryuk/encerramento da JVM, exatamente como o padrao oficial
 * recomenda (padrao herdado de {@code auth-service}/{@code catalog-service}).
 *
 * <p>Desde o plano 03-02, um LocalStack real tambem sobe (via {@link LocalStackTestSupport}) para
 * que o relay do outbox ({@code OutboxRelay}, D-59/D-60, 05-04) e o listener da saga ({@code
 * ReservationCommandListener}, Fase 5) tenham uma fila de verdade para falar — sem isso, todo
 * {@code PUT /inventory/{productId}} (que grava {@code STOCK_ADJUSTED} no outbox) e todo comando
 * {@code ReserveStock}/{@code ReleaseStock} dos testes existentes nao teriam fila real para
 * publicar/consumir. Spring Cloud AWS nao oferece {@code @ServiceConnection} para LocalStack
 * (03-RESEARCH.md Pitfall A), entao as propriedades sao registradas a mao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
abstract class AbstractIntegrationTest {

    // Mesma tag de imagem Postgres que o docker-compose.yml usa.
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.registerAwsProperties(registry);
    }

    @Autowired
    protected MockMvc mockMvc;
}

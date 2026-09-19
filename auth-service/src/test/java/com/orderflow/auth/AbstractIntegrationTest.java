package com.orderflow.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Classe base de integração — sobe um PostgreSQL real via Testcontainers (Wave 0 da estratégia de
 * validação desta fase) e reutiliza o mesmo contexto Spring para toda a suíte. O container é
 * {@code static} de propósito: reiniciar o contexto Spring rotacionaria o par de chaves RSA
 * gerado no startup (JwtIssuerConfig, D-02) e invalidaria tokens emitidos no meio da suíte
 * (01-RESEARCH.md Pitfall 4).
 *
 * <p><b>Padrão "singleton container" (Testcontainers docs), não {@code @Testcontainers}/
 * {@code @Container}</b>: com duas ou mais classes {@code *IT} estendendo esta base
 * (introduzido pelo plano 01-04 com {@code CompanyControllerIT}), a extensão JUnit 5
 * {@code @Testcontainers} para um campo {@code static} para o {@code stop()} desse container no
 * {@code afterAll} de CADA classe de teste — como o campo é herdado e compartilhado, a primeira
 * classe a terminar (ex.: {@code AuthControllerIT}) derrubava o container no meio da suíte, e a
 * classe seguinte ({@code CompanyControllerIT}) recriava um container novo (ID e porta
 * diferentes) enquanto o pool de conexões do Hikari ainda apontava para a porta antiga —
 * produzindo {@code Connection refused} e 401/500 aleatórios em qualquer teste que dependesse de
 * login. Iniciar o container manualmente num bloco {@code static} (sem {@code @Testcontainers})
 * garante um único {@code start()} por JVM, nunca interrompido entre classes; a limpeza fica a
 * cargo do Ryuk/encerramento da JVM, exatamente como o padrão oficial recomenda.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    // Mesma tag de imagem Postgres que o docker-compose.yml do plano 01-03 vai usar.
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    static {
        postgres.start();
    }

    @Autowired
    protected MockMvc mockMvc;
}

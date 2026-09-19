package com.orderflow.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Classe base de integração — sobe um PostgreSQL real via Testcontainers (Wave 0 da estratégia de
 * validação desta fase) e reutiliza o mesmo contexto Spring para toda a suíte. O container é
 * {@code static} de propósito: reiniciar o contexto Spring rotacionaria o par de chaves RSA
 * gerado no startup (JwtIssuerConfig, D-02) e invalidaria tokens emitidos no meio da suíte
 * (01-RESEARCH.md Pitfall 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
abstract class AbstractIntegrationTest {

    // Mesma tag de imagem Postgres que o docker-compose.yml do plano 01-03 vai usar.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Autowired
    protected MockMvc mockMvc;
}

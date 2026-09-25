package com.orderflow.order;

import com.orderflow.order.support.DownstreamStubServer;
import com.orderflow.order.support.OrderTestInfrastructure;
import com.orderflow.order.support.TestJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Classe base de integração — Postgres real via Testcontainers e o stub HTTP compartilhado
 * ({@link OrderTestInfrastructure}), registrados à mão via {@link DynamicPropertySource} em vez
 * de {@code @ServiceConnection}: as classes de socket real da Task 2 precisam do MESMO container
 * e do MESMO stub, e {@code @ServiceConnection}/{@code @Testcontainers} encerraria o container ao
 * final da primeira classe de teste da suíte (mesmo motivo documentado em
 * {@code AbstractIntegrationTest} do catalog-service para o padrão "singleton container").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        OrderTestInfrastructure.register(registry);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected static DownstreamStubServer stub() {
        return OrderTestInfrastructure.STUB_SERVER;
    }
}

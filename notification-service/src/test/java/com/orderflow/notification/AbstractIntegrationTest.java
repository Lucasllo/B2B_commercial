package com.orderflow.notification;

import com.orderflow.notification.support.LocalStackTestSupport;
import com.orderflow.notification.support.TestJwt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Classe base de integracao — sobe um LocalStack real via Testcontainers e reutiliza o mesmo
 * contexto Spring para toda a suite. O container e {@code static} de proposito.
 *
 * <p><b>Padrao "singleton container" (Testcontainers docs), nao {@code @Testcontainers}/
 * {@code @Container}</b>: com duas ou mais classes {@code *IT} estendendo esta base, a extensao
 * JUnit 5 {@code @Testcontainers} para um campo {@code static} chamaria {@code stop()} desse
 * container no {@code afterAll} de CADA classe de teste — como o campo e herdado e compartilhado,
 * a primeira classe a terminar derrubaria o container no meio da suite, e a classe seguinte
 * recriaria um container novo (ID e porta diferentes) enquanto o cliente AWS ja inicializado
 * ainda apontava para a porta antiga — produzindo erros de conexao aleatorios em qualquer teste
 * subsequente. Iniciar o container manualmente num bloco {@code static} (sem
 * {@code @Testcontainers}), dentro de {@link LocalStackTestSupport}, garante um unico
 * {@code start()} por JVM, nunca interrompido entre classes; a limpeza fica a cargo do
 * Ryuk/encerramento da JVM, exatamente como o padrao oficial recomenda (padrao herdado de
 * {@code inventory-service}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.registerAwsProperties(registry);
    }

    @Autowired
    protected MockMvc mockMvc;
}

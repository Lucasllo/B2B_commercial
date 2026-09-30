package com.orderflow.e2e;

import com.orderflow.e2e.support.E2eHttp;
import com.orderflow.e2e.support.E2eInfrastructure;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike do risco de colisão de configuração (05-RESEARCH.md Pitfall 2, D-68) — escrito e verde
 * ANTES de qualquer teste da saga. Prova que os dois contextos Spring reais deste módulo, apesar de
 * os dois jars trazerem {@code application.yml} na raiz do classpath, nunca leem a configuração um
 * do outro: cada um com seu próprio {@code spring.application.name}, schema do Flyway e título do
 * OpenAPI (D-68, mitigação registrada em {@link E2eInfrastructure}).
 */
class E2eContextsSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(E2eContextsSmokeIT.class);

    @Test
    void orderContextHasItsOwnApplicationNameSchemaAndOpenApiTitle() {
        var env = E2eInfrastructure.ORDER_CONTEXT.getEnvironment();
        assertThat(env.getProperty("spring.application.name")).isEqualTo("order-service");
        assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("order");
        assertThat(env.getProperty("orderflow.openapi.title")).isEqualTo("OrderFlow — Order Service API");
    }

    @Test
    void inventoryContextHasItsOwnApplicationNameSchemaAndOpenApiTitle() {
        var env = E2eInfrastructure.INVENTORY_CONTEXT.getEnvironment();
        assertThat(env.getProperty("spring.application.name")).isEqualTo("inventory-service");
        assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("inventory");
        assertThat(env.getProperty("orderflow.openapi.title")).isEqualTo("OrderFlow — Inventory Service API");
    }

    @Test
    void orderSchemaHasExactlyItsOwnTwoMigrationsApplied() {
        // [Rule 1 - Bug] Flyway grava uma linha extra (type SCHEMA, version NULL) para o evento de
        // criação do schema — "version IS NOT NULL" isola só as migrações versionadas de verdade
        // (V1, V2), como o <behavior> desta task pede.
        List<String> versions = E2eInfrastructure.jdbc().queryForList(
                "SELECT version FROM \"order\".flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void inventorySchemaHasExactlyItsOwnThreeMigrationsApplied() {
        // [Rule 1 - Bug] mesmo motivo do teste acima — exclui a linha de criação de schema.
        List<String> versions = E2eInfrastructure.jdbc().queryForList(
                "SELECT version FROM inventory.flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3");
    }

    /**
     * Documenta o risco da pesquisa como real (05-RESEARCH.md Pitfall 2): os dois jars
     * (order-service, inventory-service) trazem {@code application.yml} na raiz do classpath deste
     * módulo — {@code ClassLoader.getResources} devolve as DUAS URLs (nunca uma mescla), e {@code
     * getResource} (singular) devolveria só a primeira. Nenhuma asserção depende de QUAL delas vence
     * — só que o risco existe, e que {@link E2eInfrastructure} nunca depende dessa resolução
     * ambígua (cada contexto usa {@code --spring.config.location} apontando para o arquivo real).
     */
    @Test
    void classpathCarriesBothServicesApplicationYmlDocumentingTheCollisionRisk() throws IOException {
        Enumeration<URL> resources = getClass().getClassLoader().getResources("application.yml");
        List<URL> urls = Collections.list(resources);
        assertThat(urls.size()).isGreaterThanOrEqualTo(2);

        URL firstResolved = getClass().getClassLoader().getResource("application.yml");
        log.info("Risco de colisão de classpath (05-RESEARCH.md Pitfall 2): {} URLs de "
                        + "application.yml encontradas ({}); getResource (singular) resolveria "
                        + "primeiro: {}",
                urls.size(), urls, firstResolved);
    }

    @Test
    void orderHealthEndpointRespondsWithoutToken() {
        E2eHttp.Response response = E2eHttp.get(E2eInfrastructure.orderBaseUrl() + "/actuator/health", null);
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void inventoryHealthEndpointRespondsWithoutToken() {
        E2eHttp.Response response = E2eHttp.get(E2eInfrastructure.inventoryBaseUrl() + "/actuator/health", null);
        assertThat(response.status()).isEqualTo(200);
    }
}

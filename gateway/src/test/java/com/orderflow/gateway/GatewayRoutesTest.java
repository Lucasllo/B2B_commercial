package com.orderflow.gateway;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tabela de rotas estáticas do Gateway lida do {@code application.yml} real, sem subir o Gateway
 * (TEST-01, D-05, D-87): cada um dos cinco serviços tem uma rota de negócio com {@code StripPrefix=1}
 * e uma rota de docs com {@code SetPath=/v3/api-docs} apontando para o MESMO upstream, as rotas de docs
 * vêm antes das de negócio, e o Gateway não declara nenhuma configuração de segurança — quem valida o
 * JWT é cada serviço. O encaminhamento real para um upstream HTTP é provado em {@code GatewayRoutingIT}.
 */
class GatewayRoutesTest {

    private static final String PREFIX = "spring.cloud.gateway.server.webmvc.routes[";

    /** serviço -> predicados Path da rota de negócio. */
    private static final Map<String, String> BUSINESS_PATHS = Map.of(
            "auth", "Path=/api/auth/**,/api/companies/**",
            "catalog", "Path=/api/products/**",
            "inventory", "Path=/api/inventory/**",
            "notification", "Path=/api/notifications/**",
            "order", "Path=/api/orders/**");

    private static Properties yaml;
    private static List<Route> routes;

    private record Route(String id, String uri, String predicates, List<String> filters) {
    }

    @BeforeAll
    static void loadRoutes() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        yaml = factory.getObject();
        routes = new ArrayList<>();
        for (int i = 0; yaml.getProperty(PREFIX + i + "].id") != null; i++) {
            List<String> predicates = new ArrayList<>();
            for (int p = 0; yaml.getProperty(PREFIX + i + "].predicates[" + p + "]") != null; p++) {
                predicates.add(yaml.getProperty(PREFIX + i + "].predicates[" + p + "]"));
            }
            List<String> filters = new ArrayList<>();
            for (int f = 0; yaml.getProperty(PREFIX + i + "].filters[" + f + "]") != null; f++) {
                filters.add(yaml.getProperty(PREFIX + i + "].filters[" + f + "]"));
            }
            routes.add(new Route(yaml.getProperty(PREFIX + i + "].id"), yaml.getProperty(PREFIX + i + "].uri"),
                    String.join(";", predicates), filters));
        }
    }

    private static Route route(String id) {
        return routes.stream().filter(route -> route.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("rota ausente: " + id));
    }

    @Test
    void thereAreExactlyFiveDocsRoutesAndFiveBusinessRoutes() {
        assertThat(routes).hasSize(10);
        assertThat(routes.stream().map(Route::id)).containsExactlyInAnyOrder(
                "auth-docs", "catalog-docs", "inventory-docs", "notification-docs", "order-docs",
                "auth-service-route", "catalog-service-route", "inventory-service-route",
                "notification-service-route", "order-service-route");
    }

    @Test
    void everyServiceHasABusinessRouteThatStripsTheApiPrefixOnItsOwnPaths() {
        BUSINESS_PATHS.forEach((service, expectedPath) -> {
            Route route = route(service + "-service-route");

            assertThat(route.predicates()).isEqualTo(expectedPath);
            assertThat(route.filters()).containsExactly("StripPrefix=1");
        });
    }

    @Test
    void everyServiceHasADocsRouteThatSetsThePathToTheSpecAndOnlyMatchesItsOwnSpec() {
        BUSINESS_PATHS.keySet().forEach(service -> {
            Route route = route(service + "-docs");

            assertThat(route.predicates()).isEqualTo("Path=/docs/" + service + "/v3/api-docs");
            assertThat(route.filters()).containsExactly("SetPath=/v3/api-docs");
        });
    }

    @Test
    void theDocsRouteAndTheBusinessRouteOfAServiceShareTheSameUpstream() {
        BUSINESS_PATHS.keySet().forEach(service -> {
            String upstream = route(service + "-service-route").uri();

            assertThat(route(service + "-docs").uri()).isEqualTo(upstream);
            assertThat(upstream).contains("orderflow.gateway.upstream." + service);
        });
    }

    @Test
    void docsRoutesComeBeforeBusinessRoutes() {
        int lastDocs = -1;
        int firstBusiness = Integer.MAX_VALUE;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id().endsWith("-docs")) {
                lastDocs = i;
            } else {
                firstBusiness = Math.min(firstBusiness, i);
            }
        }

        assertThat(lastDocs).isLessThan(firstBusiness);
    }

    @Test
    void theGatewayDeclaresNoSecurityConfigurationBecauseEachServiceValidatesTheJwtItself() {
        assertThat(yaml.stringPropertyNames()).noneMatch(name -> name.startsWith("spring.security"));
    }
}

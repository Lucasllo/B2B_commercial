package com.orderflow.order.client;

import com.orderflow.order.config.ClientConfig;
import com.orderflow.order.config.ClientProperties.Downstream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários (sem contexto Spring) dos dois clientes de saída contra uma porta TCP fechada
 * — provam que o cliente de produção falha fechado mesmo quando a conexão nunca chega a se
 * estabelecer, não só quando o vizinho responde algo inesperado (04-02 Task 2). Monta o
 * {@link RestClient} pela MESMA fábrica de produção ({@link ClientConfig#buildRestClient}), não uma
 * cópia — do contrário o teste não provaria nada sobre o timeout configurado em produção.
 */
class DownstreamClientsTest {

    @Test
    void authServiceClientAgainstClosedPortThrowsAuthServiceUnavailableException() throws IOException {
        RestClient restClient = ClientConfig.buildRestClient(closedPortDownstream());
        AuthServiceClient client = new AuthServiceClient(restClient);

        assertThatThrownBy(() -> client.getCreditLimit(UUID.randomUUID(), "test-token"))
                .isInstanceOf(AuthServiceUnavailableException.class);
    }

    @Test
    void catalogServiceClientAgainstClosedPortThrowsCatalogServiceUnavailableException() throws IOException {
        RestClient restClient = ClientConfig.buildRestClient(closedPortDownstream());
        CatalogServiceClient client = new CatalogServiceClient(restClient);

        assertThatThrownBy(() -> client.findOrderableProduct(UUID.randomUUID(), "test-token"))
                .isInstanceOf(CatalogServiceUnavailableException.class);
    }

    /** Abre e imediatamente fecha um {@link ServerSocket} em 127.0.0.1 — a porta devolvida recusa toda conexão. */
    private Downstream closedPortDownstream() throws IOException {
        int port;
        try (ServerSocket serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = serverSocket.getLocalPort();
        }
        return new Downstream(URI.create("http://127.0.0.1:" + port), Duration.ofMillis(500), Duration.ofMillis(500));
    }
}

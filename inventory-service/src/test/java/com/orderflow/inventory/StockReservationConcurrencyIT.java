package com.orderflow.inventory;

import com.orderflow.inventory.stock.StockReservationRepository;
import com.orderflow.inventory.stock.dto.StockResponse;
import com.orderflow.inventory.support.LocalStackTestSupport;
import com.orderflow.inventory.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova do Success Criteria 3 da fase (D-22): requisicoes HTTP simultaneas por socket real,
 * disparadas de threads virtuais liberadas ao mesmo tempo por uma {@link CyclicBarrier}, contra
 * as ultimas unidades de um produto, nunca reservam mais do que o disponivel.
 *
 * <p>Esta classe <b>nao</b> estende {@link AbstractIntegrationTest} de proposito: a base usa
 * {@code MockMvc}, que despacha a requisicao dentro do processo sem um socket real -- nao atende
 * D-22 ("forca probatoria maior para o avaliador externo"). Aqui o servidor embutido sobe em
 * porta aleatoria e o disparo usa {@code java.net.http.HttpClient} da propria JDK, sobre um
 * executor de uma thread virtual por tarefa. Uma barreira dimensionada para o numero exato de
 * contendores forca a sobreposicao real das requisicoes: sem ela, a primeira requisicao pode
 * comitar antes de a segunda comecar, e o teste vira um laco sequencial que passa sempre pelo
 * motivo errado (02-RESEARCH.md Pitfall 3).
 *
 * <p>As threads virtuais desta classe sao exclusivamente do lado do cliente que dispara as
 * requisicoes -- nenhuma configuracao de threads virtuais do lado do servidor e ativada
 * (02-RESEARCH.md Pitfall 5, {@code inventory-service/src/main/resources/application.yml}).
 *
 * <p>[Rule 1 - Bug, achado durante 05-04]: por nao estender {@link AbstractIntegrationTest}, esta
 * classe cria um {@code ApplicationContext} Spring PROPRIO (assinatura de configuracao diferente —
 * sem {@code @AutoConfigureMockMvc}) — o cache de contexto de teste do Spring mantem esse contexto
 * VIVO em segundo plano depois que as tres tasks desta classe terminam, inclusive o {@code
 * ReservationCommandListener} PROPRIO deste contexto, que continua consumindo mensagens da MESMA
 * fila {@code inventory-commands-queue} (compartilhada via {@link LocalStackTestSupport}) usada por
 * todas as outras suites. Ate 05-04, nenhuma suite posterior fazia asserção via JDBC que dependesse
 * de qual listener processou a mensagem — a partir de 05-04 (TombstoneReleaseIT), um comando podia
 * ser "roubado" por este listener zumbi e gravado no banco ERRADO (um {@code PostgreSQLContainer}
 * SEPARADO do de {@link AbstractIntegrationTest}), fazendo a asserção da suite seguinte nunca ver o
 * efeito. Reutilizar o MESMO container Postgres elimina o "split-brain": não importa qual dos dois
 * contextos processa a mensagem, o efeito cai sempre no mesmo banco que os testes consultam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestJwt.Config.class)
class StockReservationConcurrencyIT {

    // [Rule 1 - Bug] Reaproveita o MESMO container Postgres de AbstractIntegrationTest (mesmo
    // pacote, campo package-private acessivel) — nunca um container SEPARADO, que criaria dois
    // bancos divergentes por causa do ApplicationContext proprio desta classe (ver javadoc acima).
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = AbstractIntegrationTest.postgres;

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        LocalStackTestSupport.registerAwsProperties(registry);
    }

    // Cliente HTTP compartilhado por toda a classe, reutilizado em todas as requisicoes -- e
    // baseado em E/S nao bloqueante, entao nao trava as threads virtuais que o usam.
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Test
    void umaUnidadeComVinteContendoresReservaExatamenteUma() throws Exception {
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        setStock(productId, 1, token);

        List<ContenderResult> results = fireContenders(productId, 20, 1, "sc1-", token);

        assertNoServerErrors(results);
        long successCount = countSuccesses(results);
        assertThat(successCount).isEqualTo(1);

        StockResponse finalState = getStock(productId, token);
        assertThat(finalState.quantityReserved()).isEqualTo(1);
        assertThat(finalState.quantityReserved()).isLessThanOrEqualTo(finalState.quantityOnHand());
        assertUnreleasedRowsMatchSuccesses(productId, results);
    }

    @Test
    void tresUnidadesComVinteContendoresReservamExatamenteTres() throws Exception {
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        setStock(productId, 3, token);

        List<ContenderResult> results = fireContenders(productId, 20, 1, "sc3-", token);

        assertNoServerErrors(results);
        long successCount = countSuccesses(results);
        assertThat(successCount).isEqualTo(3);

        StockResponse finalState = getStock(productId, token);
        assertThat(finalState.quantityReserved()).isEqualTo(3);
        assertThat(finalState.quantityReserved()).isLessThanOrEqualTo(finalState.quantityOnHand());
        assertUnreleasedRowsMatchSuccesses(productId, results);
    }

    @Test
    void cincoUnidadesComContendoresDeDoisNuncaUltrapassaCinco() throws Exception {
        UUID productId = UUID.randomUUID();
        String token = TestJwt.sellerAdminToken();
        setStock(productId, 5, token);

        List<ContenderResult> results = fireContenders(productId, 20, 2, "sc5-", token);

        assertNoServerErrors(results);
        long successCount = countSuccesses(results);
        // Quantidades desiguais (2 por contendor sobre 5 disponiveis): o numero exato de sucessos
        // depende de qual disputa cada tentativa venceu, mas a soma das quantidades efetivamente
        // gravadas nunca pode ultrapassar o estoque -- no maximo dois contendores de 2 unidades
        // cabem em 5.
        assertThat(successCount).isLessThanOrEqualTo(2);

        StockResponse finalState = getStock(productId, token);
        assertThat(finalState.quantityReserved()).isEqualTo((int) (successCount * 2));
        assertThat(finalState.quantityReserved()).isLessThanOrEqualTo(finalState.quantityOnHand());
        assertUnreleasedRowsMatchSuccesses(productId, results);
    }

    private void assertNoServerErrors(List<ContenderResult> results) {
        // O conjunto de respostas aceitas ja inclui a disputa esgotada (503, D-21) alem do sucesso
        // (200) e da recusa por estoque insuficiente (409) -- nenhuma delas e erro de servidor.
        assertThat(results).allSatisfy(result ->
                assertThat(result.statusCode()).isIn(200, 409, 503));
    }

    private long countSuccesses(List<ContenderResult> results) {
        return results.stream().filter(r -> r.statusCode() == 200).count();
    }

    private void assertUnreleasedRowsMatchSuccesses(UUID productId, List<ContenderResult> results) {
        long unreleasedRows = results.stream()
                .filter(r -> r.statusCode() == 200)
                .filter(r -> stockReservationRepository
                        .findByProductIdAndReservationId(productId, r.reservationId())
                        .map(reservation -> !reservation.isReleased())
                        .orElse(false))
                .count();
        assertThat(unreleasedRows).isEqualTo(countSuccesses(results));
    }

    private List<ContenderResult> fireContenders(UUID productId, int contenders, int quantityEach,
                                                  String reservationPrefix, String token) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        List<ContenderResult> results = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = IntStream.range(0, contenders)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        String reservationId = reservationPrefix + i;
                        try {
                            barrier.await();
                            int statusCode = reserve(productId, reservationId, quantityEach, token);
                            results.add(new ContenderResult(reservationId, statusCode));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } catch (BrokenBarrierException | IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }, executor))
                    .toList();
            futures.forEach(CompletableFuture::join);
        }
        return results;
    }

    private int reserve(UUID productId, String reservationId, int quantity, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/inventory/" + productId + "/reservations"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"reservationId\":\"%s\",\"quantity\":%d}".formatted(reservationId, quantity)))
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void setStock(UUID productId, int quantityOnHand, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/inventory/" + productId))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"quantityOnHand\":%d}".formatted(quantityOnHand)))
                .build();
        HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Falha ao definir estoque de teste: HTTP " + response.statusCode());
        }
    }

    private StockResponse getStock(UUID productId, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/inventory/" + productId))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Falha ao consultar estoque de teste: HTTP " + response.statusCode());
        }
        return parseStockResponse(response.body());
    }

    /**
     * Parse minimo, sem depender de um {@code ObjectMapper} injetado (esta classe nao estende
     * {@link AbstractIntegrationTest} e nao tem acesso a um bean Jackson pre-configurado por
     * conveniencia): o corpo de {@link StockResponse} e plano, sempre com estas quatro chaves.
     */
    private StockResponse parseStockResponse(String json) {
        return new StockResponse(
                UUID.fromString(extractString(json, "productId")),
                extractInt(json, "quantityOnHand"),
                extractInt(json, "quantityReserved"),
                extractInt(json, "quantityAvailable"));
    }

    private String extractString(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Campo ausente na resposta de teste: " + field);
        }
        return matcher.group(1);
    }

    private int extractInt(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Campo ausente na resposta de teste: " + field);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private record ContenderResult(String reservationId, int statusCode) {
    }
}

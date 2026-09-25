package com.orderflow.order.support;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * Dispara requisições HTTP realmente simultâneas por socket real, sincronizadas por uma {@link
 * CyclicBarrier} dimensionada para o número exato de requisições — sem a barreira, a primeira
 * requisição pode comitar antes de a segunda começar, e o teste vira um laço sequencial que passa
 * pelo motivo errado (mesma justificativa de {@code StockReservationConcurrencyIT},
 * inventory-service, 02-RESEARCH.md Pitfall 3). As threads virtuais desta classe são só do lado
 * do cliente que dispara as requisições. Reaproveitada pelo plano {@code 04-04}.
 */
public final class ConcurrentRequests {

    // Cliente compartilhado, versão HTTP_1_1 — mesmo estilo dos clientes de produção
    // (ClientConfig), não bloqueia as threads virtuais que o usam.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private ConcurrentRequests() {
    }

    public record Result(int index, int statusCode, String body) {
    }

    public static List<Result> fireTogether(List<HttpRequest> requests) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(requests.size());
        List<Result> results = Collections.synchronizedList(new ArrayList<>(requests.size()));
        for (int i = 0; i < requests.size(); i++) {
            results.add(null);
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = IntStream.range(0, requests.size())
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await();
                            HttpResponse<String> response = HTTP_CLIENT.send(
                                    requests.get(i), HttpResponse.BodyHandlers.ofString());
                            results.set(i, new Result(i, response.statusCode(), response.body()));
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
        return List.copyOf(results);
    }
}

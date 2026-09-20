package com.orderflow.inventory;

import com.orderflow.inventory.stock.Inventory;
import com.orderflow.inventory.stock.InventoryRepository;
import com.orderflow.inventory.stock.InventoryService;
import com.orderflow.inventory.stock.StockReservationRepository;
import com.orderflow.inventory.stock.dto.StockResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prova barata e focada de que o mecanismo de reexecucao por conflito de lock otimista
 * ({@code InventoryService.reserve}/{@code release}, {@code RetryConfig}, D-20) realmente
 * reexecuta contra uma transacao nova e uma releitura da linha, em vez de morrer em silencio na
 * mesma transacao condenada pela primeira falha de versao.
 *
 * <p>Esta classe existe porque a ordem entre o aspecto de reexecucao ({@code @Retryable}) e o de
 * transacao ({@code @Transactional}) sobre o mesmo metodo e uma suposicao registrada em
 * {@code 02-RESEARCH.md} §Assumptions Log A1, nao um fato ja verificado — o padrao
 * {@code @EnableRetry(order = Ordered.LOWEST_PRECEDENCE)} em {@code RetryConfig} e a correcao
 * documentada, mas so um teste que isola o mecanismo (e nao apenas infere sucesso do resultado
 * agregado, como {@code StockReservationConcurrencyIT} faz) prova que ela realmente se sustenta
 * nesta combinacao exata de Spring Boot/Spring Retry. Se este teste falhar enquanto os demais
 * passam, o suspeito imediato e a ordem de advisor declarada em {@code RetryConfig}.
 *
 * <p>Estende {@link AbstractIntegrationTest} porque nao precisa de socket real: o objetivo aqui e
 * isolar o mecanismo de reexecucao chamando o bean injetado diretamente, exatamente como
 * {@code InventoryController} faz — atravessando o proxy Spring corretamente, sem o risco de
 * autoinvocacao (02-RESEARCH.md Pitfall 2). A pilha HTTP completa e responsabilidade de
 * {@code StockReservationConcurrencyIT}.
 */
@Import(InventoryRetryContentionIT.RetryListenerTestConfig.class)
class InventoryRetryContentionIT extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private CountingRetryListener countingRetryListener;

    @Test
    void duasReservasConcorrentesSobreEstoqueDezAmbasSucedemComReexecucao() throws Exception {
        UUID productId = UUID.randomUUID();
        inventoryService.setStock(productId, 10);
        countingRetryListener.reset();

        List<StockResponse> results = reserveConcurrently(productId, 2, "res-dupla-");

        assertThat(results).hasSize(2);
        StockResponse finalState = inventoryService.getStock(productId);
        assertThat(finalState.quantityReserved()).isEqualTo(2);
        assertThat(stockReservationRepository.findByProductIdAndReservationId(productId, "res-dupla-0")).isPresent();
        assertThat(stockReservationRepository.findByProductIdAndReservationId(productId, "res-dupla-1")).isPresent();
        // Prova direta de que a reexecucao de fato ocorreu -- nao apenas que o resultado agregado
        // bateu por coincidencia de timing: pelo menos um conflito de versao real foi capturado
        // pelo aspecto de retry e disparou uma nova tentativa.
        assertThat(countingRetryListener.errorCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void dezReservasConcorrentesSobreEstoqueDezTerminamComDezSucessos() throws Exception {
        UUID productId = UUID.randomUUID();
        inventoryService.setStock(productId, 10);
        countingRetryListener.reset();

        List<StockResponse> results = reserveConcurrently(productId, 10, "res-dez-");

        assertThat(results).hasSize(10);
        StockResponse finalState = inventoryService.getStock(productId);
        assertThat(finalState.quantityReserved()).isEqualTo(10);
        long linhasNaoLiberadas = IntStream.range(0, 10)
                .mapToObj(i -> stockReservationRepository.findByProductIdAndReservationId(productId, "res-dez-" + i))
                .filter(Optional::isPresent)
                .count();
        assertThat(linhasNaoLiberadas).isEqualTo(10);
    }

    @Test
    void conflitoDeVersaoForcadoDeliberadamenteNaoImpedeSucessoDaProximaChamada() {
        UUID productId = UUID.randomUUID();
        inventoryService.setStock(productId, 10);

        // 1. Le a linha fora de uma transacao mais ampla: o repositorio Spring Data abre e fecha
        //    sua propria transacao por chamada, entao o retorno ja vem desanexado (detached), com
        //    a versao lida naquele instante presa em memoria.
        Inventory staleCopy = inventoryRepository.findByProductId(productId).orElseThrow();

        // 2. Por outro caminho -- o proprio metodo reexecutavel, chamado normalmente pelo bean --
        //    a linha e alterada e comitada, avancando a versao gravada no banco.
        StockResponse afterOtherPath = inventoryService.reserve(productId, "res-outro-caminho", 1);
        assertThat(afterOtherPath.quantityReserved()).isEqualTo(1);

        // 3. Tenta persistir a copia OBSOLETA diretamente pelo repositorio -- de proposito fora do
        //    metodo reexecutavel, para isolar so o mecanismo de deteccao do JPA: a excecao de
        //    conflito de versao e disparada de forma deterministica, sem depender de timing de
        //    threads.
        staleCopy.reserve(1);
        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 4. Uma nova chamada ao metodo reexecutavel, sem se apoiar em instancia obsoleta, le a
        //    linha ja atualizada e sucede normalmente -- o conflito do passo anterior nao deixou a
        //    linha, a transacao ou o servico em estado inconsistente, e a reexecucao configurada
        //    (RetryConfig) obtem transacao nova e releitura a cada tentativa.
        StockResponse finalResponse = inventoryService.reserve(productId, "res-final", 1);
        assertThat(finalResponse.quantityReserved()).isEqualTo(2);
    }

    private List<StockResponse> reserveConcurrently(UUID productId, int contenders, String reservationPrefix) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
            List<CompletableFuture<StockResponse>> futures = IntStream.range(0, contenders)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } catch (BrokenBarrierException e) {
                            throw new IllegalStateException(e);
                        }
                        return inventoryService.reserve(productId, reservationPrefix + i, 1);
                    }, executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    @TestConfiguration
    static class RetryListenerTestConfig {

        @Bean
        CountingRetryListener countingRetryListener() {
            return new CountingRetryListener();
        }
    }

    /**
     * Conta quantas vezes o Spring Retry capturou uma excecao e decidiu reexecutar. Registrado
     * como bean {@link RetryListener}, e coletado automaticamente por {@code RetryConfiguration}
     * (ativada por {@code @EnableRetry}) e aplicado a todo metodo anotado com {@code @Retryable}
     * no contexto -- nenhuma alteracao em codigo de producao e necessaria para observar isso.
     */
    static class CountingRetryListener implements RetryListener {

        private final AtomicInteger errors = new AtomicInteger();

        void reset() {
            errors.set(0);
        }

        int errorCount() {
            return errors.get();
        }

        @Override
        public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            errors.incrementAndGet();
        }
    }
}

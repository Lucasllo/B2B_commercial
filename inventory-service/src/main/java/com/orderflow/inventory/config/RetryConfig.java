package com.orderflow.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Habilita o Spring Retry para {@code InventoryService.reserve}/{@code release}/{@code setStock}
 * (D-20). Nao existe uso anterior de Spring Retry neste repositorio — este e o primeiro.
 *
 * <p>A ordem explicita nao e cosmetica: sem uma ordem estritamente menor que a do advisor
 * transacional, a ordem entre o aspecto de reexecucao e o aspecto de transacao fica indefinida
 * (empate resolvido de forma nao garantida pelo framework), e o risco concreto e que todas as
 * tentativas rodem dentro da mesma transacao ja condenada pela primeira falha de versao — a
 * segunda tentativa morre imediatamente em vez de reler a linha com a versao nova, e a reexecucao
 * configurada nunca acontece de verdade (02-RESEARCH.md Pitfall 1).
 *
 * <p>{@code Ordered.LOWEST_PRECEDENCE - 1} (nao {@code Ordered.LOWEST_PRECEDENCE}) e o valor
 * correto: {@code @EnableTransactionManagement} (o que a autoconfiguracao de
 * {@code @Transactional} do Spring Boot importa) tem ordem padrao {@code LOWEST_PRECEDENCE}
 * (2147483647, confirmado por inspecao de bytecode de {@code spring-tx-6.2.19.jar} nesta fase —
 * revisao de codigo CR-01). Usar {@code LOWEST_PRECEDENCE} aqui empataria com esse valor em vez
 * de ficar estritamente abaixo, reintroduzindo a mesma ambiguidade que este comentario alega
 * evitar — o valor abaixo garante, sem depender de coincidencia de ordem de registro de beans,
 * que o aspecto de reexecucao envolve o de transacao por fora, com transacao nova e releitura a
 * cada tentativa. A prova comportamental dessa relacao (nao apenas do valor numerico) esta em
 * {@code InventoryRetryContentionIT}, que forca um conflito de versao determinístico e usa um
 * {@code RetryListener} de teste para confirmar que a reexecucao realmente acontece.
 */
@Configuration
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE - 1)
public class RetryConfig {
}

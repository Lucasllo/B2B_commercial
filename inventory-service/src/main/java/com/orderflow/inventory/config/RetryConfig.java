package com.orderflow.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Habilita o Spring Retry para {@code InventoryService.reserve}/{@code release}/{@code setStock}
 * (D-20). Nao existe uso anterior de Spring Retry neste repositorio — este e o primeiro.
 *
 * <p>A ordem {@code Ordered.LOWEST_PRECEDENCE} nao e cosmetica: sem ela, a ordem entre o aspecto
 * de reexecucao e o aspecto de transacao fica indefinida, e o risco concreto e que todas as
 * tentativas rodem dentro da mesma transacao ja condenada pela primeira falha de versao — a
 * segunda tentativa morre imediatamente em vez de reler a linha com a versao nova, e a reexecucao
 * configurada nunca acontece de verdade (02-RESEARCH.md Pitfall 1). A ordem mais baixa faz o
 * aspecto de reexecucao envolver o de transacao por fora, o que garante transacao nova e releitura
 * a cada tentativa.
 */
@Configuration
@EnableRetry(order = Ordered.LOWEST_PRECEDENCE)
public class RetryConfig {
}

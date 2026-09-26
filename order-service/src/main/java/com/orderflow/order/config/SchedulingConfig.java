package com.orderflow.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita {@code @Scheduled} para o relay do outbox ({@code OutboxRelayJob}, D-59) e, a partir de
 * 05-04, para o job de timeout da saga (D-63) — ambos batem nesta mesma flag, sem configuração
 * adicional por job.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

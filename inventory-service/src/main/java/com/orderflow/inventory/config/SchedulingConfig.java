package com.orderflow.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita {@code @Scheduled} para o relay do outbox ({@code OutboxRelayJob}, D-59). Duplicado do
 * equivalente do order-service (D-62) — mesma flag, sem configuração adicional por job.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

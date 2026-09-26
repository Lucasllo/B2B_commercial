package com.orderflow.order.saga.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disparo {@code @Scheduled} do relay (D-59). Bean SEPARADO de {@link OutboxRelay} de propósito:
 * se {@code run()} vivesse no mesmo bean que {@code publishPendingBatch()} e chamasse esse método
 * diretamente, a chamada bypassaria o proxy transacional do Spring (auto-invocação de método
 * {@code @Transactional} — mesma regra já documentada em 02-RESEARCH.md Pitfall 2 para o
 * {@code InventoryService}).
 */
@Component
public class OutboxRelayJob {

    private final OutboxRelay outboxRelay;

    public OutboxRelayJob(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.relay-interval}")
    public void run() {
        outboxRelay.publishPendingBatch();
    }
}

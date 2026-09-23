package com.orderflow.notification.history.messaging;

import com.orderflow.notification.history.NotificationService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/**
 * Unico ponto de entrada de dados do notification-service — ele nunca e chamado por REST por
 * outro servico e nunca faz chamada sincrona de saida (o servico e um sink puro). O parametro e
 * {@code String} de proposito: o corpo chega verbatim (o conversor padrao entrega o texto
 * original quando o tipo alvo e {@code String}), a fila e de fan-out e vai receber outros tipos de
 * evento nas Fases 5 e 6, e a decisao de como interpretar o corpo fica no servico.
 */
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @SqsListener("${orderflow.notifications.queue-name}")
    public void onMessage(String payload) {
        notificationService.record(payload);
    }
}

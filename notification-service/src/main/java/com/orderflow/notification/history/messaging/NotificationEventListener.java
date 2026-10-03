package com.orderflow.notification.history.messaging;

import com.orderflow.notification.history.InvalidNotificationEventException;
import com.orderflow.notification.history.NotificationService;
import com.orderflow.notification.observability.CorrelationContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Unico ponto de entrada de dados do notification-service — ele nunca e chamado por REST por
 * outro servico e nunca faz chamada sincrona de saida (o servico e um sink puro). O parametro e
 * {@code String} de proposito: o corpo chega verbatim (o conversor padrao entrega o texto
 * original quando o tipo alvo e {@code String}), a fila e de fan-out e vai receber outros tipos de
 * evento nas Fases 5 e 6, e a decisao de como interpretar o corpo fica no servico.
 *
 * <p>Sem fila de mensagens mortas ({@code DLQ-01}, v2), o descarte com log e a unica defesa contra
 * uma mensagem que nunca poderia ser processada ficar voltando para sempre: retornar normalmente
 * faz o Spring Cloud AWS confirmar e apagar a mensagem. Qualquer outra excecao (ex.: falha de
 * gravacao no DynamoDB) nao e capturada aqui — sem confirmacao, a mensagem volta a fila depois do
 * timeout de visibilidade, e como a gravacao e idempotente por chave, a reentrega e segura.
 *
 * <p>D-107 / WR-03: o descarte continua (fila sem DLQ, {@code DLQ-01} e v2), mas o WARN agora traz
 * {@code orderId} e {@code eventType} do evento {@code ORDER_*} invalido, ja sanitizados pelo
 * servico, para que a linha do tempo incompleta de um pedido possa ser investigada. Nunca se loga
 * o payload bruto.
 *
 * <p>O atributo SQS {@code correlationId} abre o escopo de MDC de cada mensagem (D-94, D-97): o ID
 * so entra no log se casar o formato esperado; a thread do listener e reutilizada, entao o escopo
 * restaura o MDC ao fim (T-07-23).
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final String queueName;

    public NotificationEventListener(NotificationService notificationService,
                                      @Value("${orderflow.notifications.queue-name}") String queueName) {
        this.notificationService = notificationService;
        this.queueName = queueName;
    }

    @SqsListener("${orderflow.notifications.queue-name}")
    public void onMessage(String payload,
                          @Header(name = CorrelationContext.SQS_ATTRIBUTE, required = false) String correlationId) {
        try (var scope = CorrelationContext.open(correlationId)) {
            log.info("Mensagem recebida da fila '{}'", queueName);
            try {
                notificationService.record(payload);
            } catch (InvalidNotificationEventException e) {
                log.warn("Mensagem descartada da fila '{}' orderId={} eventType={}: {}", queueName,
                        orDash(e.getOrderId()), orDash(e.getEventType()), e.getMessage());
            }
        }
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    /** Sobrecarga sem atributo e sem {@code @SqsListener}: delega com ID nulo (o escopo gera um UUID). */
    public void onMessage(String payload) {
        onMessage(payload, null);
    }
}

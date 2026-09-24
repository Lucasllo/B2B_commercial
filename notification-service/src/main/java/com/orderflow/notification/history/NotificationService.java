package com.orderflow.notification.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.orderflow.notification.history.dto.NotificationResponse;
import com.orderflow.notification.history.dto.StockAdjustedEvent;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Mapeia evento -> registro, monta a mensagem legivel, valida o evento antes de gravar, e ordena
 * a leitura por ordem cronologica. Uma falha do repositorio no {@code save} nao e capturada aqui —
 * ela propaga como esta, para que a mensagem nao seja confirmada e o SQS a entregue de novo (a
 * gravacao e idempotente por chave, entao a reentrega e segura).
 */
@Service
public class NotificationService {

    static final String SORT_KEY_SEPARATOR = "#";

    private static final int SANITIZED_VALUE_MAX_LENGTH = 64;

    // DynamoDB rejeita itens acima de 400 KB (ValidationException). Um rawPayload assim de grande
    // (ex.: um campo extra inesperado) faz o putItem falhar permanentemente, e como o listener trata
    // qualquer excecao alem de InvalidNotificationEventException como transitoria, a mensagem volta
    // para a fila e reentrega para sempre. Rejeitar cedo, antes do parse, corta esse laco (WR-02).
    private static final int MAX_RAW_PAYLOAD_BYTES = 64 * 1024;

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final ObjectReader eventReader;

    public NotificationService(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        // FAIL_ON_TRAILING_TOKENS detecta conteudo depois do valor JSON valido — uma mensagem
        // venenosa nao pode passar por valida so porque o primeiro objeto do corpo e bem formado.
        this.eventReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public void record(String rawPayload) {
        if (rawPayload.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_PAYLOAD_BYTES) {
            throw new InvalidNotificationEventException(
                    "Corpo da mensagem excede o tamanho maximo permitido de " + MAX_RAW_PAYLOAD_BYTES + " bytes");
        }

        JsonNode tree;
        try {
            tree = eventReader.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            throw new InvalidNotificationEventException("Corpo da mensagem nao e um JSON valido");
        }

        if (tree == null || !tree.isObject()) {
            throw new InvalidNotificationEventException("Corpo da mensagem nao e um objeto JSON");
        }

        StockAdjustedEvent event;
        try {
            event = objectMapper.treeToValue(tree, StockAdjustedEvent.class);
        } catch (JsonProcessingException e) {
            throw new InvalidNotificationEventException("Evento com um ou mais campos em formato invalido");
        }

        validate(event);

        String message = "Estoque do produto %s ajustado de %d para %d %s"
                .formatted(event.productId(), event.previousQuantityOnHand(), event.newQuantityOnHand(),
                        event.newQuantityOnHand() == 1 ? "unidade" : "unidades");

        NotificationRecord record = new NotificationRecord();
        record.setProductId(event.productId().toString());
        record.setSortKey(event.eventType() + SORT_KEY_SEPARATOR + event.eventId());
        record.setEventId(event.eventId().toString());
        record.setEventType(event.eventType());
        record.setRawPayload(serializeTree(tree));
        record.setMessage(message);
        record.setOccurredAt(event.occurredAt());
        record.setRecordedAt(Instant.now());

        // Sem expressao de condicao: PutItem substitui completamente o item de mesma chave, o que
        // da a sobrescrita em reentrega de graca. Nao ha leitura previa "ja processei este
        // evento?" — a chave deterministica ja resolve a idempotencia.
        notificationRepository.save(record);
    }

    private void validate(StockAdjustedEvent event) {
        String eventType = event.eventType();
        if (eventType == null || !eventType.equals(StockAdjustedEvent.EVENT_TYPE)) {
            throw new InvalidNotificationEventException(
                    "Tipo de evento nao suportado: " + sanitizeForLog(eventType));
        }
        if (event.eventId() == null) {
            throw new InvalidNotificationEventException("Campo eventId ausente");
        }
        if (event.productId() == null) {
            throw new InvalidNotificationEventException("Campo productId ausente");
        }
        if (event.occurredAt() == null) {
            throw new InvalidNotificationEventException("Campo occurredAt ausente");
        }
        if (event.previousQuantityOnHand() == null) {
            throw new InvalidNotificationEventException("Campo previousQuantityOnHand ausente");
        }
        if (event.newQuantityOnHand() == null) {
            throw new InvalidNotificationEventException("Campo newQuantityOnHand ausente");
        }
        if (event.previousQuantityOnHand() < 0 || event.newQuantityOnHand() < 0) {
            throw new InvalidNotificationEventException("Quantidade negativa nao e permitida");
        }
    }

    /**
     * O valor recebido vem de fora e acabaria numa linha de log WARN do listener — troca qualquer
     * caractere de controle (inclusive NEL, U+0085) e os separadores de linha/paragrafo Unicode
     * U+2028 e U+2029 (que varios agregadores de log tratam como quebra) por {@code _} e corta em
     * 64 caracteres para impedir injecao de linha de log e mensagens de exceção
     * desproporcionalmente grandes.
     */
    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "(ausente)";
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]", "_");
        return sanitized.length() > SANITIZED_VALUE_MAX_LENGTH
                ? sanitized.substring(0, SANITIZED_VALUE_MAX_LENGTH)
                : sanitized;
    }

    private String serializeTree(JsonNode tree) {
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification event payload", e);
        }
    }

    public List<NotificationResponse> history(UUID productId) {
        List<NotificationRecord> records = new ArrayList<>(
                notificationRepository.findByProductId(productId.toString()));
        // A Query devolve os itens em ordem de sort key (um UUID aleatorio no fim, portanto nao
        // cronologica), e a fila padrao do SQS nao garante ordem de entrega — a ordem cronologica
        // e restaurada aqui, na leitura.
        records.sort(Comparator.comparing(NotificationRecord::getOccurredAt)
                .thenComparing(NotificationRecord::getSortKey));

        List<NotificationResponse> responses = new ArrayList<>();
        for (NotificationRecord record : records) {
            responses.add(NotificationResponse.from(record, parsePayload(record.getRawPayload())));
        }
        return responses;
    }

    private JsonNode parsePayload(String rawPayload) {
        try {
            return objectMapper.readTree(rawPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored notification payload", e);
        }
    }
}

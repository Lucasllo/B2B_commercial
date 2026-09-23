package com.orderflow.notification.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.orderflow.notification.history.dto.NotificationResponse;
import com.orderflow.notification.history.dto.StockAdjustedEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mapeia evento -> registro, monta a mensagem legivel, e ordena a leitura por ordem cronologica.
 * Nesta task (Task 1) um corpo invalido simplesmente propaga a excecao do Jackson (envolvida em
 * {@link IllegalArgumentException}); a Task 2 transforma isso em descarte controlado via
 * {@link InvalidNotificationEventException}.
 */
@Service
public class NotificationService {

    static final String SORT_KEY_SEPARATOR = "#";

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final ObjectReader eventReader;

    public NotificationService(NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        // FAIL_ON_TRAILING_TOKENS detecta conteudo depois do valor JSON valido (Task 2 converte
        // essa excecao do Jackson em descarte controlado).
        this.eventReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public void record(String rawPayload) {
        try {
            JsonNode tree = eventReader.readTree(rawPayload);
            StockAdjustedEvent event = objectMapper.treeToValue(tree, StockAdjustedEvent.class);

            String message = "Estoque do produto %s ajustado de %d para %d unidades"
                    .formatted(event.productId(), event.previousQuantityOnHand(), event.newQuantityOnHand());

            NotificationRecord record = new NotificationRecord();
            record.setProductId(event.productId().toString());
            record.setSortKey(event.eventType() + SORT_KEY_SEPARATOR + event.eventId());
            record.setEventId(event.eventId().toString());
            record.setEventType(event.eventType());
            record.setRawPayload(objectMapper.writeValueAsString(tree));
            record.setMessage(message);
            record.setOccurredAt(event.occurredAt());
            record.setRecordedAt(Instant.now());

            notificationRepository.save(record);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse notification event", e);
        }
    }

    public List<NotificationResponse> history(UUID productId) {
        List<NotificationRecord> records = notificationRepository.findByProductId(productId.toString());
        List<NotificationResponse> responses = new ArrayList<>();
        try {
            for (NotificationRecord record : records) {
                JsonNode payload = objectMapper.readTree(record.getRawPayload());
                responses.add(NotificationResponse.from(record, payload));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse stored notification payload", e);
        }
        return responses;
    }
}

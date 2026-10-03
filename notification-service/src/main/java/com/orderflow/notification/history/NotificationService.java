package com.orderflow.notification.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.orderflow.notification.history.dto.NotificationResponse;
import com.orderflow.notification.history.dto.OrderLifecycleEvent;
import com.orderflow.notification.history.dto.StockAdjustedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mapeia evento -> registro, monta a mensagem legivel, valida o evento antes de gravar, e ordena
 * a leitura por ordem cronologica. Uma falha do repositorio no {@code save} nao e capturada aqui —
 * ela propaga como esta, para que a mensagem nao seja confirmada e o SQS a entregue de novo (a
 * gravacao e idempotente por chave, entao a reentrega e segura).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final String SORT_KEY_SEPARATOR = "#";

    private static final int SANITIZED_VALUE_MAX_LENGTH = 64;

    // Teto dos nomes de ator (createdBy, decidedBy, shippedBy, deliveredBy) e da transportadora.
    private static final int MAX_ACTOR_LENGTH = 64;

    // Teto de reason e cancellationReason.
    private static final int MAX_REASON_LENGTH = 500;

    // decidedBy do evento de aprovacao automatica.
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private static final Pattern TRACKING_CODE = Pattern.compile("^[A-Z]{2}[0-9]{9}BR$");
    private static final Pattern CANCELLATION_CODE = Pattern.compile("^[A-Z_]{1,40}$");

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
        // USE_BIG_DECIMAL_FOR_FLOATS: o total em dinheiro nao passa por double na arvore.
        this.eventReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
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

        // O tipo decide o contrato: STOCK_ADJUSTED segue o caminho da Fase 3 (particao = produto);
        // os tipos ORDER_* seguem o NOTIFICATION_EVENT_CONTRACT (particao = pedido, D-80/D-82).
        JsonNode typeNode = tree.path("eventType");
        String eventType = typeNode.isTextual() ? typeNode.asText() : null;
        if (eventType != null && OrderLifecycleEvent.TYPES.contains(eventType)) {
            recordOrderEvent(tree);
            return;
        }
        recordStockAdjusted(tree);
    }

    private void recordStockAdjusted(JsonNode tree) {
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
        record.setEntityId(event.productId().toString());
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
        log.info("Evento registrado eventType={} entityId={}", record.getEventType(), record.getEntityId());
    }

    /**
     * Eventos de pedido (D-82): formato invalido, campo obrigatorio ausente ou fora do teto vira
     * {@link InvalidNotificationEventException} e o evento e descartado inteiro — nada e gravado
     * parcialmente. A mensagem legivel e montada aqui, a partir dos campos conferidos, nunca
     * copiada de texto livre do evento.
     */
    private void recordOrderEvent(JsonNode tree) {
        OrderLifecycleEvent event;
        try {
            event = objectMapper.treeToValue(tree, OrderLifecycleEvent.class);
        } catch (JsonProcessingException e) {
            throw new InvalidNotificationEventException("Evento com um ou mais campos em formato invalido");
        }

        requireNotNull(event.eventId(), "eventId");
        requireNotNull(event.orderId(), "orderId");
        requireNotNull(event.companyId(), "companyId");
        requireNotNull(event.occurredAt(), "occurredAt");

        String message = orderMessage(event);

        NotificationRecord record = new NotificationRecord();
        record.setEntityId(event.orderId().toString());
        record.setCompanyId(event.companyId().toString());
        record.setSortKey(event.eventType() + SORT_KEY_SEPARATOR + event.eventId());
        record.setEventId(event.eventId().toString());
        record.setEventType(event.eventType());
        record.setRawPayload(serializeTree(tree));
        record.setMessage(message);
        record.setOccurredAt(event.occurredAt());
        record.setRecordedAt(Instant.now());

        // Mesma idempotencia do caminho de produto: chave deterministica + putItem sem condicao.
        notificationRepository.save(record);
        log.info("Evento registrado eventType={} entityId={}", record.getEventType(), record.getEntityId());
    }

    private static String orderMessage(OrderLifecycleEvent event) {
        return switch (event.eventType()) {
            case OrderLifecycleEvent.ORDER_CREATED -> {
                requireText(event.createdBy(), "createdBy", MAX_ACTOR_LENGTH);
                requireNotNull(event.total(), "total");
                if (event.total().signum() < 0) {
                    throw new InvalidNotificationEventException("Campo total nao pode ser negativo");
                }
                // Valor monetario: sempre duas casas, independente de como o produtor serializou.
                yield "Pedido criado — total " + event.total().setScale(2, RoundingMode.HALF_UP).toPlainString();
            }
            case OrderLifecycleEvent.ORDER_PENDING_APPROVAL ->
                    "Pedido aguardando aprovação do vendedor — valor acima do limite de crédito disponível";
            case OrderLifecycleEvent.ORDER_APPROVED -> {
                requireText(event.decidedBy(), "decidedBy", MAX_ACTOR_LENGTH);
                boolean hasReason = event.reason() != null && !event.reason().isBlank();
                if (hasReason) {
                    requireText(event.reason(), "reason", MAX_REASON_LENGTH);
                }
                if (SYSTEM_ACTOR.equals(event.decidedBy())) {
                    yield "Pedido aprovado automaticamente — dentro do limite de crédito";
                }
                yield "Pedido aprovado pelo vendedor " + event.decidedBy()
                        + (hasReason ? " — motivo: " + event.reason() : "");
            }
            case OrderLifecycleEvent.ORDER_REJECTED -> {
                requireText(event.decidedBy(), "decidedBy", MAX_ACTOR_LENGTH);
                requireText(event.reason(), "reason", MAX_REASON_LENGTH);
                yield "Pedido rejeitado pelo vendedor " + event.decidedBy() + " — motivo: " + event.reason();
            }
            case OrderLifecycleEvent.ORDER_CONFIRMED -> {
                requireText(event.carrier(), "carrier", MAX_ACTOR_LENGTH);
                requirePattern(event.trackingCode(), "trackingCode", TRACKING_CODE);
                yield "Pedido confirmado — transportadora " + event.carrier() + ", rastreio " + event.trackingCode();
            }
            case OrderLifecycleEvent.ORDER_CANCELLED -> {
                requirePattern(event.cancellationCode(), "cancellationCode", CANCELLATION_CODE);
                requireText(event.cancellationReason(), "cancellationReason", MAX_REASON_LENGTH);
                yield "Pedido cancelado (" + event.cancellationCode() + ") — " + event.cancellationReason();
            }
            case OrderLifecycleEvent.ORDER_SHIPPED -> {
                requireText(event.shippedBy(), "shippedBy", MAX_ACTOR_LENGTH);
                yield "Pedido enviado pelo vendedor " + event.shippedBy();
            }
            case OrderLifecycleEvent.ORDER_DELIVERED -> {
                requireText(event.deliveredBy(), "deliveredBy", MAX_ACTOR_LENGTH);
                yield "Pedido entregue — registrado pelo vendedor " + event.deliveredBy();
            }
            default -> throw new InvalidNotificationEventException(
                    "Tipo de evento nao suportado: " + sanitizeForLog(event.eventType()));
        };
    }

    private static void requirePattern(String value, String field, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidNotificationEventException("Campo " + field + " ausente ou em formato invalido");
        }
    }

    private static void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new InvalidNotificationEventException("Campo " + field + " ausente");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidNotificationEventException("Campo " + field + " ausente");
        }
        if (value.length() > maxLength) {
            throw new InvalidNotificationEventException(
                    "Campo " + field + " excede o tamanho maximo de " + maxLength);
        }
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
        return toSortedResponses(notificationRepository.findByEntityId(productId.toString()));
    }

    /**
     * Linha do tempo do pedido (D-81): so registros de tipo {@code ORDER_*} — um id de produto
     * consultado por esta rota nao mostra {@code STOCK_ADJUSTED}.
     *
     * <p>Regra de leitura (D-81, D-47): o vendedor recebe a lista (vazia se nao houver registros
     * {@code ORDER_*}). O comprador so a recebe quando ha registros E todos eles tem o
     * {@code companyId} igual ao do JWT; em qualquer outro caso — pedido de outra empresa,
     * inexistente, sem eventos, ou com eventos de empresas misturadas (evento forjado, T-06-16) —
     * recebe a MESMA {@link NotificationNotFoundException}, sem revelar a existencia do pedido.
     *
     * @param callerCompanyId {@code company_id} do JWT; ignorado na visao de vendedor
     * @param sellerView      {@code true} para SELLER_ADMIN
     */
    public List<NotificationResponse> historyForOrder(UUID orderId, UUID callerCompanyId, boolean sellerView) {
        List<NotificationRecord> orderRecords = notificationRepository.findByEntityId(orderId.toString())
                .stream()
                .filter(record -> OrderLifecycleEvent.TYPES.contains(record.getEventType()))
                .toList();

        if (!sellerView) {
            String callerCompany = callerCompanyId == null ? null : callerCompanyId.toString();
            boolean ownedByCaller = !orderRecords.isEmpty() && callerCompany != null
                    && orderRecords.stream().allMatch(record -> callerCompany.equals(record.getCompanyId()));
            if (!ownedByCaller) {
                throw new NotificationNotFoundException();
            }
        }
        return toSortedResponses(orderRecords);
    }

    /**
     * Posicao do tipo no ciclo de vida do pedido, usada como desempate entre eventos do MESMO
     * instante (Pitfall 1). Na criacao, {@code ORDER_CREATED} e a decisao automatica
     * ({@code ORDER_APPROVED}/{@code ORDER_PENDING_APPROVAL}) saem com o mesmo {@code now}; sem o
     * rank, a sort key ({@code eventType#eventId}) poria {@code ORDER_APPROVED} antes de
     * {@code ORDER_CREATED}. Tipos fora do ciclo de pedido (ex.: {@code STOCK_ADJUSTED}) valem 0.
     */
    private static int lifecycleRank(String eventType) {
        if (eventType == null) {
            return 0;
        }
        return switch (eventType) {
            case OrderLifecycleEvent.ORDER_CREATED -> 1;
            case OrderLifecycleEvent.ORDER_PENDING_APPROVAL -> 2;
            case OrderLifecycleEvent.ORDER_APPROVED, OrderLifecycleEvent.ORDER_REJECTED -> 3;
            case OrderLifecycleEvent.ORDER_CONFIRMED, OrderLifecycleEvent.ORDER_CANCELLED -> 4;
            case OrderLifecycleEvent.ORDER_SHIPPED -> 5;
            case OrderLifecycleEvent.ORDER_DELIVERED -> 6;
            default -> 0;
        };
    }

    private List<NotificationResponse> toSortedResponses(List<NotificationRecord> source) {
        List<NotificationRecord> records = new ArrayList<>(source);
        // A Query devolve os itens em ordem de sort key (um UUID aleatorio no fim, portanto nao
        // cronologica), e a fila padrao do SQS nao garante ordem de entrega — a ordem cronologica
        // e restaurada aqui, na leitura.
        // TIMELINE_ORDER = occurredAt, rank de ciclo de vida, sortKey (aplicado as duas rotas).
        records.sort(Comparator.comparing(NotificationRecord::getOccurredAt)
                .thenComparingInt(record -> lifecycleRank(record.getEventType()))
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

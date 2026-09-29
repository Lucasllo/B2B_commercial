package com.orderflow.inventory.stock;

import com.orderflow.inventory.saga.messaging.dto.ReservationFailureLine;
import com.orderflow.inventory.saga.messaging.dto.ReservationLine;
import com.orderflow.inventory.saga.messaging.dto.StockReservationFailedEvent;
import com.orderflow.inventory.saga.messaging.dto.StockReservedEvent;
import com.orderflow.inventory.saga.outbox.OutboxWriter;
import com.orderflow.inventory.stock.dto.StockResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regras de estoque (INV-01, INV-02). Injecao por construtor escrita a mao — sem
 * {@code @Autowired} de campo, mesma convencao de {@code CompanyService}.
 *
 * <p><b>Regra estrutural obrigatoria:</b> {@code reserve} e {@code release} sao chamados apenas
 * de fora deste bean, pelo {@code InventoryController} — exatamente como {@code CompanyController}
 * chama {@code CompanyService} na Fase 1. {@code reserveAll} (Fase 5, D-55) e chamado so pelo
 * {@code ReservationCommandListener} (outro bean). Nenhum outro metodo desta classe pode chamar
 * {@code reserve}, {@code release} ou {@code reserveAll} internamente: uma chamada de um metodo
 * para outro do mesmo bean nao passa pelo proxy Spring, entao nem a reexecucao nem a transacao se
 * aplicam, sem erro de compilacao e sem excecao em tempo de execucao — apenas comportamento errado
 * em silencio (02-RESEARCH.md Pitfall 2; reafirmado por 05-RESEARCH.md Pattern 3/Pitfall 1 para
 * {@code reserveAll}, que por isso e construido contra os repositorios diretamente, nunca compondo
 * chamadas a {@code reserve}/{@code release}).
 */
@Service
public class InventoryService {

    /**
     * Numero de tentativas e backoff da reexecucao por conflito de versao (D-20).
     *
     * <p>Valor original de {@code 02-02} era {@code maxAttempts = 4}, {@code delay = 25},
     * {@code multiplier = 2}, sem teto de backoff. {@code InventoryRetryContentionIT} (02-03,
     * cenario de dez threads concorrentes reservando 1 unidade cada sobre um estoque de dez —
     * contencao pura de versao, sem escassez real de estoque) expos que 4 tentativas nao bastam
     * sob dez gravadores simultaneos na MESMA linha: uma tentativa esgotou as reexecucoes e
     * devolveu {@link ReservationConflictException} mesmo havendo estoque suficiente para todo
     * mundo. Aumentado para {@code maxAttempts = 10} com um teto de backoff
     * ({@code maxDelay = 200}ms) para que o crescimento exponencial nao deixe o pior caso lento
     * demais — sem o teto, a decima tentativa esperaria mais de 12 segundos. Nao e uma tentativa
     * de mascarar instabilidade: o conjunto de respostas aceito pelos testes de concorrencia ja
     * inclui a disputa esgotada (D-21) como recusa legitima; este ajuste so reduz a frequencia
     * dela no caso em que a matematica do cenario (contendores <= estoque disponivel) diz que
     * todo mundo deveria caber.
     */
    private static final int RETRY_MAX_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 20;
    private static final double RETRY_MULTIPLIER = 2.0;
    private static final long RETRY_MAX_DELAY_MS = 200;

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository stockReservationRepository;
    private final OutboxWriter outboxWriter;

    public InventoryService(InventoryRepository inventoryRepository,
                             StockReservationRepository stockReservationRepository,
                             OutboxWriter outboxWriter) {
        this.inventoryRepository = inventoryRepository;
        this.stockReservationRepository = stockReservationRepository;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Upsert (D-18): a primeira chamada para um {@code productId} cria a linha de inventario; as
     * seguintes atualizam a mesma linha. Redefinir para uma quantidade menor que a ja reservada e
     * recusado (409) em vez de deixar a constraint do banco rejeitar com uma mensagem bruta.
     * Anotado com reexecucao pelos mesmos motivos de concorrencia de {@code reserve}/
     * {@code release}: duas chamadas concorrentes para o MESMO {@code productId} novo podem
     * ambas nao encontrar linha existente e tentar inserir duas vezes, colidindo na constraint
     * {@code UNIQUE(product_id)} — a reexecucao absorve essa corrida e a tentativa perdedora
     * relê a linha ja comitada pela vencedora.
     *
     * <p>Devolve tambem a quantidade anterior ao ajuste (0 quando a linha e criada agora), lida
     * dentro desta mesma transacao — nunca por uma leitura separada de fora, porque o metodo
     * inteiro e reexecutado num conflito de versao, e so a releitura de dentro da tentativa atual
     * garante que o valor capturado nunca fica desatualizado (03-RESEARCH.md Pitfall C). Esta
     * classe nao conhece nada de mensageria: o evento de ajuste (03-02) e publicado pelo chamador
     * — {@code InventoryController} — depois que este metodo retorna e a transacao ja foi
     * commitada, nunca daqui de dentro.
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public StockAdjustmentResult setStock(UUID productId, int quantityOnHand) {
        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        int previousQuantityOnHand;
        if (inventory == null) {
            previousQuantityOnHand = 0;
            inventory = inventoryRepository.save(new Inventory(productId, quantityOnHand));
        } else {
            if (quantityOnHand < inventory.getQuantityReserved()) {
                throw new StockBelowReservedException(inventory.getQuantityReserved(), quantityOnHand);
            }
            previousQuantityOnHand = inventory.getQuantityOnHand();
            inventory.setOnHand(quantityOnHand);
            inventoryRepository.saveAndFlush(inventory);
        }
        // Capturado aqui, dentro da tentativa transacional que de fato gravou o ajuste — nao no
        // momento da publicacao do evento (fora da transacao, depois do commit), que sob dois PUT
        // concorrentes no mesmo produto pode inverter a ordem cronologica do occurredAt em relacao
        // a ordem real de commit (WR-04).
        return new StockAdjustmentResult(StockResponse.from(inventory), previousQuantityOnHand, Instant.now());
    }

    @Recover
    public StockAdjustmentResult recoverSetStock(DataAccessException ex, UUID productId, int quantityOnHand) {
        throw new ReservationConflictException();
    }

    @Transactional(readOnly = true)
    public StockResponse getStock(UUID productId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory not found"));
        return StockResponse.from(inventory);
    }

    /**
     * Reserva atomica e idempotente pelo identificador fornecido pelo chamador (D-09, D-11).
     * Reexecutavel tanto para conflito de lock otimista (outra transacao alterou a linha entre a
     * leitura e a escrita) quanto para violacao de integridade (duas requisicoes com o mesmo
     * {@code reservationId} correram e a constraint de unicidade barrou a perdedora — na
     * tentativa seguinte a checagem antecipada ja enxerga a linha comitada e devolve a resposta
     * idempotente limpa).
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public StockResponse reserve(UUID productId, String reservationId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory not found"));

        var existingReservation = stockReservationRepository.findByProductIdAndReservationId(productId, reservationId);
        if (existingReservation.isPresent()) {
            // Replica idempotente (D-11) — vale inclusive quando a reserva encontrada ja foi
            // liberada, porque o identificador e consumido uma unica vez.
            return StockResponse.from(inventory);
        }

        int available = inventory.availableQuantity();
        if (quantity > available) {
            throw new InsufficientStockException(available, quantity);
        }

        stockReservationRepository.save(new StockReservation(productId, reservationId, quantity));
        inventory.reserve(quantity);
        // saveAndFlush forca o UPDATE versionado a acontecer DENTRO desta tentativa — e o que
        // permite a reexecucao capturar o conflito de versao, em vez de so no commit da transacao.
        inventoryRepository.saveAndFlush(inventory);
        return StockResponse.from(inventory);
    }

    @Recover
    public StockResponse recoverReserve(DataAccessException ex, UUID productId, String reservationId, int quantity) {
        throw new ReservationConflictException();
    }

    /**
     * Liberacao idempotente (D-14): reserva ausente ou ja liberada e no-op silencioso que devolve
     * o estado atual sem alterar nada.
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public StockResponse release(UUID productId, String reservationId) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory not found"));

        var reservation = stockReservationRepository.findByProductIdAndReservationId(productId, reservationId).orElse(null);
        if (reservation == null || reservation.isReleased()) {
            return StockResponse.from(inventory);
        }

        inventory.release(reservation.getQuantity());
        reservation.markReleased(OffsetDateTime.now());
        inventoryRepository.saveAndFlush(inventory);
        return StockResponse.from(inventory);
    }

    @Recover
    public StockResponse recoverRelease(DataAccessException ex, UUID productId, String reservationId) {
        throw new ReservationConflictException();
    }

    /**
     * Reserva multi-item tudo-ou-nada (D-55, D-56, D-58) — mesmo par de anotacoes de {@code
     * reserve}: {@code @Retryable} com as mesmas constantes e o mesmo {@code retryFor}, e
     * {@code @Transactional} no proprio metodo, para que cada tentativa reexecutada ganhe transacao
     * nova (mesma garantia de {@code RetryConfig}/{@code InventoryRetryContentionIT}). Nunca chama
     * {@code reserve}/{@code release} deste bean (auto-invocacao pula o proxy, ver javadoc da
     * classe) — le e escreve direto pelos repositorios.
     *
     * <p><b>Quatro situacoes pelo livro {@code stock_reservations} (D-65, D-66):</b> (1) nenhuma
     * linha para o par ({@code reservationId}, produtos do comando) → reserva nova (avalia tudo
     * antes de escrever, tudo-ou-nada, D-55); (2) ALGUMA linha do livro para o {@code
     * reservationId} entre os produtos do comando ja esta liberada (LAPIDE, D-66 — um {@code
     * ReleaseStock} chegou antes ou junto deste {@code ReserveStock}, corrida da fila padrao sem
     * ordem garantida) → grava {@code StockReservationFailedEvent} com {@code reasonCode}
     * {@code RESERVATION_CANCELLED} e {@code failures} vazio, sem tocar em {@code inventory} nem
     * em {@code stock_reservations}; (3) linha para TODOS os produtos do comando e NENHUMA liberada
     * → replay idempotente: nada e alterado em {@code inventory} nem em {@code stock_reservations},
     * mas um novo {@code StockReservedEvent} (novo {@code eventId}, mesmos {@code items}) e gravado
     * no outbox e devolvido como sucesso — o comando e reentregue (D-59 entrega pelo menos uma vez)
     * ou reenviado apos uma falha anterior que nao deixou rastro (nada foi gravado no livro, ver
     * abaixo), e o consumidor (order-service) precisa do resultado de novo; (4) so parte dos
     * produtos com linha (e nenhuma liberada) e anomalia tecnica — so acontece se alguem reservar
     * por REST com o id de um pedido — {@link IllegalStateException} citando o {@code orderId}, vai
     * para reentrega e DLQ. Uma falha de negocio anterior (estoque insuficiente, produto sem linha)
     * NUNCA e reemitida identica: nada foi gravado no livro nesse caso, entao o comando cai na
     * situacao (1) e e reavaliado contra o estoque atual — se o vendedor ajustou o estoque nesse
     * meio-tempo, a resposta pode mudar de falha para sucesso (a partir de 05-04 o order-service
     * compensa um sucesso tardio para um pedido ja CANCELLED com {@code ReleaseStock}, D-64).
     *
     * <p>Passos da reserva nova: ordena as linhas por {@code productId} (ordem estavel de escrita
     * evita deadlock entre dois pedidos com produtos em comum); avalia TODAS as linhas antes de
     * escrever qualquer coisa — produto sem linha de estoque falha com {@code available=0} ({@code
     * PRODUCT_NOT_STOCKED}, D-58), quantidade acima do disponivel falha com o disponivel real
     * ({@code INSUFFICIENT_STOCK}); se houver qualquer falha, grava {@code
     * StockReservationFailedEvent} no outbox e devolve o resultado sem tocar em {@code inventory}
     * nem em {@code stock_reservations} (D-55); se todas as linhas passarem, reserva cada uma
     * (nunca altera {@code quantity_on_hand}, D-57) e grava {@code StockReservedEvent}.
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public ReservationOutcome reserveAll(UUID orderId, String reservationId, List<ReservationLine> lines) {
        List<ReservationLine> sortedLines = lines.stream()
                .sorted(Comparator.comparing(ReservationLine::productId))
                .toList();
        List<UUID> productIds = sortedLines.stream().map(ReservationLine::productId).toList();

        var existingReservations =
                stockReservationRepository.findByReservationIdAndProductIdIn(reservationId, productIds);
        if (!existingReservations.isEmpty()) {
            boolean allProductsHaveARow = existingReservations.size() == productIds.size();
            boolean anyReleased = existingReservations.stream().anyMatch(StockReservation::isReleased);
            if (anyReleased) {
                // Lápide encontrada (D-66) — um ReleaseStock chegou antes (ou junto) deste
                // ReserveStock para pelo menos um produto do comando. Resposta é sempre falha,
                // nunca reserva nada, independente de quantos produtos têm linha: a corrida da
                // fila padrão (sem ordem garantida) termina sempre com "nada reservado" para este
                // reservationId, em qualquer ordem de chegada.
                UUID eventId = UUID.randomUUID();
                Instant now = Instant.now();
                StockReservationFailedEvent event = StockReservationFailedEvent.of(eventId, now, orderId,
                        reservationId, StockReservationFailedEvent.RESERVATION_CANCELLED, List.of());
                outboxWriter.enqueue(eventId, StockReservationFailedEvent.EVENT_TYPE, orderId.toString(), event);
                return new ReservationOutcome(false, StockReservationFailedEvent.RESERVATION_CANCELLED, List.of());
            }
            if (allProductsHaveARow) {
                // Replay idempotente (D-65): nao toca em inventory nem em stock_reservations, so
                // reemite o resultado com um eventId novo.
                Instant replayNow = Instant.now();
                UUID replayEventId = UUID.randomUUID();
                StockReservedEvent replayEvent =
                        StockReservedEvent.of(replayEventId, replayNow, orderId, reservationId, sortedLines);
                outboxWriter.enqueue(replayEventId, StockReservedEvent.EVENT_TYPE, orderId.toString(), replayEvent);
                return new ReservationOutcome(true, null, List.of());
            }
            throw new IllegalStateException(
                    "stock_reservations em estado inconsistente para reservationId=" + reservationId
                            + " (pedido " + orderId + "): livro parcialmente preenchido — reserva nao processada");
        }

        Map<UUID, Inventory> inventoryByProductId = new HashMap<>();
        for (Inventory inventory : inventoryRepository.findByProductIdIn(productIds)) {
            inventoryByProductId.put(inventory.getProductId(), inventory);
        }

        List<ReservationFailureLine> failures = new ArrayList<>();
        boolean anyProductNotStocked = false;
        for (ReservationLine line : sortedLines) {
            Inventory inventory = inventoryByProductId.get(line.productId());
            if (inventory == null) {
                failures.add(new ReservationFailureLine(line.productId(), line.quantity(), 0));
                anyProductNotStocked = true;
            } else if (line.quantity() > inventory.availableQuantity()) {
                failures.add(new ReservationFailureLine(
                        line.productId(), line.quantity(), inventory.availableQuantity()));
            }
        }

        Instant now = Instant.now();
        if (!failures.isEmpty()) {
            String reasonCode = anyProductNotStocked
                    ? StockReservationFailedEvent.PRODUCT_NOT_STOCKED
                    : StockReservationFailedEvent.INSUFFICIENT_STOCK;
            UUID eventId = UUID.randomUUID();
            StockReservationFailedEvent event =
                    StockReservationFailedEvent.of(eventId, now, orderId, reservationId, reasonCode, failures);
            outboxWriter.enqueue(eventId, StockReservationFailedEvent.EVENT_TYPE, orderId.toString(), event);
            return new ReservationOutcome(false, reasonCode, failures);
        }

        for (ReservationLine line : sortedLines) {
            Inventory inventory = inventoryByProductId.get(line.productId());
            stockReservationRepository.save(new StockReservation(line.productId(), reservationId, line.quantity()));
            inventory.reserve(line.quantity());
            // saveAndFlush forca o UPDATE versionado dentro desta tentativa — mesma razao de reserve().
            inventoryRepository.saveAndFlush(inventory);
        }

        UUID eventId = UUID.randomUUID();
        StockReservedEvent event = StockReservedEvent.of(eventId, now, orderId, reservationId, sortedLines);
        outboxWriter.enqueue(eventId, StockReservedEvent.EVENT_TYPE, orderId.toString(), event);
        return new ReservationOutcome(true, null, List.of());
    }

    @Recover
    public ReservationOutcome recoverReserveAll(DataAccessException ex, UUID orderId, String reservationId,
                                                 List<ReservationLine> lines) {
        throw new ReservationConflictException();
    }

    /**
     * [Rule 1 - Bug] {@code reserveAll} lanca {@link IllegalStateException} para o livro
     * inconsistente (anomalia tecnica, nunca retentavel — nao esta em {@code retryFor}), mas o
     * aspecto de reexecucao do Spring Retry intercepta QUALQUER excecao escapando de um metodo
     * {@code @Retryable}, nao so as listadas em {@code retryFor}: sem um {@code @Recover} cujo tipo
     * de parametro corresponda, a excecao real fica soterrada por {@code
     * ExhaustedRetryException("Cannot locate recovery method")}, escondendo o motivo real do
     * chamador (achado durante o teste do livro inconsistente, IdempotentReservationIT). Este
     * metodo apenas relanca a excecao original, preservando a mensagem que cita o {@code orderId}.
     */
    /**
     * [Rule 1 - Bug] {@code reserveAll} lanca {@link IllegalStateException} para o livro
     * inconsistente (anomalia tecnica, nunca retentavel — nao esta em {@code retryFor}), mas o
     * aspecto de reexecucao do Spring Retry intercepta QUALQUER excecao escapando de um metodo
     * {@code @Retryable}, nao so as listadas em {@code retryFor}: sem um {@code @Recover} cujo tipo
     * de parametro corresponda, a excecao real fica soterrada por {@code
     * ExhaustedRetryException("Cannot locate recovery method")}, escondendo o motivo real do
     * chamador (achado durante o teste do livro inconsistente, IdempotentReservationIT). Este
     * metodo apenas relanca a excecao original, preservando a mensagem que cita o {@code orderId}.
     */
    @Recover
    public ReservationOutcome recoverReserveAllInconsistentBook(IllegalStateException ex, UUID orderId,
                                                                 String reservationId, List<ReservationLine> lines) {
        throw ex;
    }

    /**
     * Compensacao do {@code ReleaseStock} (D-63, D-66, 05-04) — mesmo par de anotacoes de {@code
     * reserveAll}: {@code @Retryable} com as mesmas constantes/{@code retryFor}, e {@code
     * @Transactional} no proprio metodo. Nunca chama {@code reserve}/{@code release}/{@code
     * reserveAll} deste bean (auto-invocacao pula o proxy, javadoc da classe) — le e escreve direto
     * pelos repositorios, uma linha por vez, ordenadas por {@code productId} (mesma ordem estavel
     * de {@code reserveAll}, evita deadlock entre comandos com produtos em comum).
     *
     * <p>Por linha: sem linha no livro para o par ({@code reservationId}, {@code productId}) →
     * grava uma LAPIDE ({@link StockReservation#tombstone}) com a quantidade pedida pelo comando —
     * inclusive para produto sem linha de {@code inventory} (D-58, {@code TOMBSTONE_FK=dropped},
     * V3); linha existente e JA liberada → nada (idempotente, D-14 estendido pela lapide); linha
     * existente e AINDA viva → devolve a quantidade ({@code inventory.release}, linha de {@code
     * inventory} ausente aqui e anomalia tecnica — {@link IllegalStateException}, nunca deveria
     * acontecer: uma reserva viva so nasce depois de ler a linha de estoque) e marca a linha
     * liberada. {@code saveAndFlush} em cada escrita forca o INSERT/UPDATE a acontecer DENTRO desta
     * tentativa — o que permite a reexecucao capturar tanto o conflito de versao do {@code
     * inventory} quanto a violacao de unicidade de uma lapide colidindo com um {@code ReserveStock}
     * concorrente no mesmo {@code (product_id, reservation_id)} (D-66: em qualquer ordem de
     * chegada, o resultado final e sempre "nada reservado" — {@code reserveAll} responde
     * {@code RESERVATION_CANCELLED} para qualquer linha ja liberada que encontrar).
     *
     * <p>Nenhum evento de resposta e gravado no outbox — o order-service nao espera resultado da
     * compensacao (05-04-PLAN.md, interfaces {@code SAGA_MESSAGE_CONTRACT}).
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public void releaseAll(UUID orderId, String reservationId, List<ReservationLine> lines) {
        List<ReservationLine> sortedLines = lines.stream()
                .sorted(Comparator.comparing(ReservationLine::productId))
                .toList();

        for (ReservationLine line : sortedLines) {
            var existing = stockReservationRepository.findByProductIdAndReservationId(line.productId(), reservationId);
            if (existing.isPresent()) {
                StockReservation reservation = existing.get();
                if (reservation.isReleased()) {
                    continue;
                }
                Inventory inventory = inventoryRepository.findByProductId(line.productId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Inventory ausente para produto com reserva viva productId=" + line.productId()
                                        + " reservationId=" + reservationId + " (pedido " + orderId + ")"));
                inventory.release(reservation.getQuantity());
                reservation.markReleased(OffsetDateTime.now());
                // saveAndFlush forca as escritas versionadas/unicas DENTRO desta tentativa — mesma
                // razao de reserveAll.
                inventoryRepository.saveAndFlush(inventory);
                stockReservationRepository.saveAndFlush(reservation);
            } else {
                stockReservationRepository.saveAndFlush(
                        StockReservation.tombstone(line.productId(), reservationId, line.quantity(), OffsetDateTime.now()));
            }
        }
    }

    @Recover
    public void recoverReleaseAll(DataAccessException ex, UUID orderId, String reservationId,
                                   List<ReservationLine> lines) {
        throw new ReservationConflictException();
    }
}

package com.orderflow.inventory.stock;

import com.orderflow.inventory.stock.dto.StockResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Regras de estoque (INV-01, INV-02). Injecao por construtor escrita a mao — sem
 * {@code @Autowired} de campo, mesma convencao de {@code CompanyService}.
 *
 * <p><b>Regra estrutural obrigatoria:</b> {@code reserve} e {@code release} sao chamados apenas
 * de fora deste bean, pelo {@code InventoryController} — exatamente como {@code CompanyController}
 * chama {@code CompanyService} na Fase 1. Nenhum outro metodo desta classe pode chamar
 * {@code reserve} ou {@code release} internamente: uma chamada de um metodo para outro do mesmo
 * bean nao passa pelo proxy Spring, entao nem a reexecucao nem a transacao se aplicam, sem erro de
 * compilacao e sem excecao em tempo de execucao — apenas comportamento errado em silencio
 * (02-RESEARCH.md Pitfall 2).
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

    public InventoryService(InventoryRepository inventoryRepository,
                             StockReservationRepository stockReservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.stockReservationRepository = stockReservationRepository;
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
     */
    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = RETRY_MAX_ATTEMPTS,
            backoff = @Backoff(delay = RETRY_DELAY_MS, multiplier = RETRY_MULTIPLIER, maxDelay = RETRY_MAX_DELAY_MS))
    @Transactional
    public StockResponse setStock(UUID productId, int quantityOnHand) {
        Inventory inventory = inventoryRepository.findByProductId(productId).orElse(null);
        if (inventory == null) {
            inventory = inventoryRepository.save(new Inventory(productId, quantityOnHand));
        } else {
            if (quantityOnHand < inventory.getQuantityReserved()) {
                throw new StockBelowReservedException(inventory.getQuantityReserved(), quantityOnHand);
            }
            inventory.setOnHand(quantityOnHand);
            inventoryRepository.saveAndFlush(inventory);
        }
        return StockResponse.from(inventory);
    }

    @Recover
    public StockResponse recoverSetStock(DataAccessException ex, UUID productId, int quantityOnHand) {
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
}

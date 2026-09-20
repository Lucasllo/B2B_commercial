package com.orderflow.inventory.stock;

/**
 * Lancada pelo metodo de recuperacao quando todas as tentativas de reexecucao de
 * reserva/liberacao se esgotam (D-21) — 503, semanticamente distinto do 409 de estoque
 * insuficiente (D-10). Nao carrega dado nenhum: a disputa transitoria nao tem um numero util a
 * expor, apenas a instrucao implicita de tentar novamente.
 */
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException() {
        super("Reservation attempt exhausted retries due to concurrent contention");
    }
}

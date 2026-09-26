-- Fase 5 (D-60, D-62): Transactional Outbox duplicado no inventory-service — mesma tabela, mesmo
-- indice parcial da V2 do order-service, so o pacote Java muda. Nesta fase (05-02) recebe o
-- resultado da reserva (StockReserved/StockReservationFailed); a partir de 05-04 o STOCK_ADJUSTED
-- da Fase 3 tambem passa a sair por aqui, fechando o dual-write conhecido (D-29/D-30).
--
-- id nao tem DEFAULT: e o eventId do payload, atribuido pela aplicacao (OutboxWriter) antes de
-- gravar. published_at nulo = ainda nao enviado; attempts/last_error registram falha de envio por
-- evento, sem derrubar o lote inteiro (T-05-02). O indice parcial serve exatamente a consulta do
-- relay (WHERE published_at IS NULL), sem indexar linhas ja publicadas que o relay nunca mais toca.
CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error VARCHAR(500)
);

CREATE INDEX idx_outbox_event_unpublished ON outbox_event(attempts, created_at) WHERE published_at IS NULL;

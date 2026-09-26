-- Fase 5 (ORD-04, D-48 a D-55, D-59, D-61): entrada na saga de reserva de estoque pelo padrão
-- Transactional Outbox. Toda entrada em aprovação (automática ou manual) passa a gravar
-- status = RESERVING e uma linha em outbox_event na MESMA transação — nunca existe pedido
-- "APPROVED sem comando" (D-50). APPROVED continua no enum/CHECK como um passo lógico da decisão
-- (decided_by/decided_at/reason), nunca um estado em que um pedido novo repousa.

-- (1) Larga o CHECK antigo (nome gerado pelo Postgres a partir da coluna, V1__init_order_schema.sql)
-- e recria com os 9 estados da ORD-10, RESERVING incluído entre APPROVED e CONFIRMED (D-49).
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status IN
    ('CREATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'RESERVING',
     'CONFIRMED', 'CANCELLED', 'SHIPPED', 'DELIVERED'));

-- (2) Colunas da saga (D-53): resultado gravado em colunas PRÓPRIAS, separadas da decisão do
-- vendedor (decided_by/decided_at/reason permanecem intocados). reservation_started_at (D-49,
-- SAGA_TIMEOUT_CLOCK) é o relógio do timeout da Fase 5-04 — conta a partir da entrada em
-- RESERVING, nunca de decided_at, porque um pedido APPROVED legado migrado abaixo tem decided_at
-- antigo e seria cancelado por timeout no primeiro ciclo se o timeout contasse a partir dele.
-- cancellation_code ganha CHECK com os quatro códigos de toda a fase (CANCELLATION_CODE_CHECK) —
-- só INSUFFICIENT_STOCK/PRODUCT_NOT_STOCKED/RESERVATION_CANCELLED/RESERVATION_TIMEOUT são gravados
-- por código nesta fase (05-01), mas o CHECK já lista os quatro para que 05-02/05-03/05-04 não
-- precisem de outra migração de ALTER só para isso (mesma técnica de status acima).
ALTER TABLE orders ADD COLUMN reservation_started_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN cancellation_code VARCHAR(40)
    CONSTRAINT chk_orders_cancellation_code CHECK (cancellation_code IN
        ('INSUFFICIENT_STOCK', 'PRODUCT_NOT_STOCKED', 'RESERVATION_CANCELLED', 'RESERVATION_TIMEOUT'));
ALTER TABLE orders ADD COLUMN cancellation_reason VARCHAR(500);
ALTER TABLE orders ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN confirmed_at TIMESTAMPTZ;

CREATE INDEX idx_orders_status_reservation_started_at ON orders(status, reservation_started_at);

-- (3) Tabela outbox_event (D-59, D-62 — duplicada em cada serviço, sem módulo compartilhado). O
-- id é o eventId do payload, atribuído pela aplicação (OutboxWriter) — sem DEFAULT, ao contrário
-- das demais tabelas deste schema. published_at nulo = ainda não enviado; attempts/last_error
-- registram falha de envio por evento, sem derrubar o lote inteiro (T-05-02). O índice parcial
-- serve exatamente a consulta do relay (WHERE published_at IS NULL), sem indexar linhas já
-- publicadas que o relay nunca mais toca.
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

-- (4) Migração de dados (D-51): todo pedido legado em APPROVED (só possível se este banco já foi
-- usado antes desta migração — Fase 4) nasceu antes de a saga existir e precisa entrar nela agora,
-- na subida, para nunca ficar órfão. Roda como SQL puro dentro do Flyway (antes de a aplicação e o
-- relay existirem) — nunca um backfill em Java. Um gen_random_uuid() por pedido serve tanto de id
-- da linha do outbox quanto de eventId do payload (mesma regra de ReservationSagaStarter). O
-- payload segue o contrato ReserveStock de <interfaces> (05-01-PLAN.md): eventId, eventType,
-- occurredAt, orderId, reservationId (= orderId em texto, RESERVATION_ID_SCOPE=scope-per-product),
-- items (productId/quantity, na ordem de line_number).
WITH legacy_approved AS (
    SELECT id, gen_random_uuid() AS event_id
    FROM orders
    WHERE status = 'APPROVED'
)
INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at, published_at, attempts)
SELECT
    la.event_id,
    la.id::text,
    'ReserveStock',
    json_build_object(
        'eventId', la.event_id,
        'eventType', 'ReserveStock',
        'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'orderId', la.id,
        'reservationId', la.id::text,
        'items', (
            SELECT json_agg(json_build_object('productId', oi.product_id, 'quantity', oi.quantity)
                             ORDER BY oi.line_number)
            FROM order_items oi
            WHERE oi.order_id = la.id
        )
    )::text,
    now(),
    NULL,
    0
FROM legacy_approved la;

UPDATE orders SET status = 'RESERVING', reservation_started_at = now() WHERE status = 'APPROVED';

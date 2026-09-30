-- Fase 6 (ORD-07, ORD-08, ORD-09, D-70, D-73, D-76): transportadora simulada e código de rastreio
-- gravados na MESMA transação do CONFIRMED, mais as colunas de expedição e entrega que os
-- endpoints de /ship e /deliver (06-02) preencherão. Todas as colunas nascem aqui, de uma vez, para
-- que o contrato do OrderResponse mude uma única vez.

-- (1) Colunas novas (D-73, D-76). carrier/tracking_code são preenchidos por OrderSagaService ao
-- confirmar; shipped_*/delivered_* são preenchidos pelos endpoints de 06-02. shipped_by/delivered_by
-- guardam o sub do JWT (texto, como decided_by) — nunca um UUID/FK.
ALTER TABLE orders ADD COLUMN carrier VARCHAR(64);
ALTER TABLE orders ADD COLUMN tracking_code VARCHAR(13);
ALTER TABLE orders ADD COLUMN shipped_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN shipped_by VARCHAR(64);
ALTER TABLE orders ADD COLUMN delivered_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN delivered_by VARCHAR(64);

-- (2) Backfill (LEGACY_TRACKING_BACKFILL): o volume postgres-data persiste entre execuções, então
-- um banco de desenvolvimento anterior à Fase 6 pode conter pedidos CONFIRMED (a Fase 5 já os
-- produzia) sem transportadora. Sem este backfill a CHECK do item (3) falharia na subida. Só afeta
-- essas bases: recebem 'Transportadora Legada' e um código 'LG' + 9 dígitos derivados de
-- md5(id::text) + 'BR' — casa o padrão, mas NÃO reproduz o dígito S10 nem o algoritmo Java.
-- Precisa vir ANTES das CHECKs.
UPDATE orders
SET carrier = 'Transportadora Legada',
    tracking_code = 'LG'
        || lpad((abs(('x' || substr(md5(id::text), 1, 8))::bit(32)::bigint) % 1000000000)::text, 9, '0')
        || 'BR'
WHERE status IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')
  AND carrier IS NULL;

-- (3) Invariantes de banco (D-70): nunca existe pedido CONFIRMED, SHIPPED ou DELIVERED sem
-- transportadora e rastreio, mesmo que o código erre; e o código, quando presente, tem o formato
-- Correios/S10. TRACKING_CODE_UNIQUENESS=probabilistic: NÃO há UNIQUE em tracking_code — com
-- 26² x 10^8 combinações uma colisão por aniversário só aparece perto de 3x10^5 pedidos, e uma
-- violação de unicidade dentro da transação do CONFIRMED a derrubaria a cada reentrega do mesmo
-- StockReserved (o código é determinístico), criando um laço de reentrega até a DLQ (Pitfall 7).
ALTER TABLE orders ADD CONSTRAINT chk_orders_tracking_code_format
    CHECK (tracking_code IS NULL OR tracking_code ~ '^[A-Z]{2}[0-9]{9}BR$');
ALTER TABLE orders ADD CONSTRAINT chk_orders_shipping_assigned
    CHECK (status NOT IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')
           OR (carrier IS NOT NULL AND tracking_code IS NOT NULL));

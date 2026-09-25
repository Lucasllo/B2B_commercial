-- Cria as tabelas orders, order_items e company_credit_lock no schema order (D-01: uma instância
-- Postgres, um schema por serviço). total/unit_price/subtotal em NUMERIC(19,2) seguem a convenção
-- monetária D-06 herdada da Fase 1 — nunca inteiro em centavos, nem double.
--
-- status é gravado como texto com CHECK listando os 8 valores da ORD-10 (defesa contra corrupção
-- silenciosa por reordenação do enum, 02-RESEARCH.md Pitfall 4, mesma técnica de products.status) —
-- declarados todos agora mesmo que só CREATED/PENDING_APPROVAL/APPROVED/REJECTED sejam alcançados
-- por código nesta fase (D-45), para que as Fases 5/6 não precisem de uma migração de ALTER só para
-- acrescentar os demais.
--
-- decided_by é VARCHAR(64), nunca UUID/FK: guarda o literal "SYSTEM" (decisão automática, D-45) ou
-- o claim sub (UUID como texto) do SELLER_ADMIN que decidiu manualmente (D-46) — não existe tabela
-- de usuários neste schema, mesma regra de referência opaca de company_id/product_id (D-15).
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('CREATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
         'CONFIRMED', 'CANCELLED', 'SHIPPED', 'DELIVERED')),
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by VARCHAR(64),
    decided_at TIMESTAMPTZ,
    reason VARCHAR(500)
);

CREATE INDEX idx_orders_company_id_created_at ON orders(company_id, created_at DESC);
CREATE INDEX idx_orders_status_created_at ON orders(status, created_at DESC);

-- Snapshot do item no momento da criação (D-43): productId/sku/name/unitPrice congelados, nunca
-- recalculados depois. UNIQUE (order_id, product_id) é a defesa no banco contra produto repetido
-- no mesmo pedido (D-44), além da checagem em memória em OrderCreationService.
CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    product_id UUID NOT NULL,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal >= 0),
    UNIQUE (order_id, line_number),
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);

-- Linha de trava por empresa (D-40): a transação de criação/decisão faz upsert idempotente
-- (ON CONFLICT DO NOTHING) e SELECT ... FOR UPDATE nesta linha antes de somar a exposição e
-- decidir o status — serializa por empresa, nunca entre empresas diferentes. Sem FK para a tabela
-- companies do auth-service (schema diferente, mesma regra de referência opaca de company_id acima).
CREATE TABLE company_credit_lock (
    company_id UUID PRIMARY KEY
);

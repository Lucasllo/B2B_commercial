-- Cria a tabela products no schema catalog (D-01: uma instância Postgres, um schema por
-- serviço). price em NUMERIC(19,2) segue a convenção monetária D-06 herdada da Fase 1 — nunca
-- inteiro em centavos, nem double. status é gravado como texto com CHECK em vez de confiar
-- apenas no mapeamento do JPA — defesa contra corrupção silenciosa por reordenação do enum
-- (02-RESEARCH.md Pitfall 4), mesma técnica já usada na coluna role da Fase 1.
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    price NUMERIC(19,2) NOT NULL CHECK (price >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'DISCONTINUED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A listagem do BUYER filtra por status em toda requisição (D-24) — índice dedicado.
CREATE INDEX idx_products_status ON products(status);

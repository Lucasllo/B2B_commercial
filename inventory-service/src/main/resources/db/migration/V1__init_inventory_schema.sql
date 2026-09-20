-- Cria as tabelas inventory e stock_reservations no schema inventory (D-01: uma instancia
-- Postgres, um schema por servico). A coluna version e o mecanismo de lock otimista do JPA
-- (@Version): cada UPDATE bem-sucedido a incrementa e a compara na clausula WHERE, transformando
-- duas atualizacoes concorrentes numa vencedora e numa que precisa reexecutar (RetryConfig,
-- D-20). chk_inventory_not_oversold e a rede de seguranca final da invariante central da fase:
-- ainda que toda a logica de aplicacao falhe, o banco recusa a linha. product_id e referencia
-- opaca ao catalogo (D-15) — nao existe FK para o catalog-service, porque os dois servicos tem
-- schemas independentes e nenhuma validacao sincrona cruza a fronteira.
CREATE TABLE inventory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL UNIQUE,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
    quantity_reserved INTEGER NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_inventory_not_oversold CHECK (quantity_reserved <= quantity_on_hand)
);

-- Livro de idempotencia de reserva/liberacao (D-11, D-14). Tabela separada, nao uma coluna em
-- inventory, porque um mesmo produto recebe muitas reservas concorrentes — e e exatamente a
-- forma que a saga da Fase 5 vai ler e escrever.
--
-- RESERVATION_ID_SCOPE=scope-per-product (confirmado no checkpoint da Task 1 deste plano):
-- unicidade por par (product_id, reservation_id) — um mesmo reservation_id pode ser reutilizado
-- em produtos diferentes (ex.: todos os itens de um pedido usando o id do pedido como
-- reservation_id, um por produto reservado).
CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES inventory(product_id),
    reservation_id VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    released BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    CONSTRAINT uq_stock_reservations_product_reservation UNIQUE (product_id, reservation_id)
);

CREATE INDEX idx_stock_reservations_product_id ON stock_reservations(product_id);

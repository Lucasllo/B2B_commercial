-- Fase 6 (06-03, D-75, D-57): a expedicao tem efeito real no estoque. Ate a Fase 5 o estoque so era
-- RESERVADO (quantity_reserved) — a baixa fisica (quantity_on_hand) foi adiada de proposito (D-57)
-- para quando houvesse expedicao. Agora um ShipStock consumido da inventory-commands-queue baixa
-- quantity_on_hand e quantity_reserved pelo mesmo valor e marca a linha do livro como EXPEDIDA.
--
-- A marca `shipped` no livro e a guarda de idempotencia: reentregar o ShipStock (mesmo eventId ou
-- outro) encontra a linha ja expedida e nao baixa de novo. A quantidade baixada vem SEMPRE da linha
-- do livro (stock_reservations.quantity), nunca do corpo do comando.
--
-- chk_inventory_not_oversold (V1: quantity_reserved <= quantity_on_hand) continua valido: a
-- expedicao decrementa as duas colunas pelo MESMO valor, entao a diferenca (disponivel) nao muda e
-- nenhuma das duas fica negativa (CHECKs >= 0 da V1).
--
-- Uma reserva nao pode estar ao mesmo tempo liberada e expedida (Pitfall 5): liberar devolve a
-- quantidade a quantity_reserved, expedir a baixa de quantity_on_hand — fazer os dois decrementaria
-- quantity_reserved duas vezes e "roubaria" a reserva de outro pedido. A CHECK abaixo e a rede de
-- seguranca final caso a logica de aplicacao (guardas isShipped em release/releaseAll) falhe.
--
-- Reversibilidade (custosa): desfazer exige DROP das duas colunas e das duas CHECKs, e rever
-- release/releaseAll/reserveAll/shipAll juntos; linhas ja expedidas perderiam a marca.
ALTER TABLE stock_reservations ADD COLUMN shipped BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stock_reservations ADD COLUMN shipped_at TIMESTAMPTZ;

ALTER TABLE stock_reservations
    ADD CONSTRAINT chk_stock_reservations_not_released_and_shipped CHECK (NOT (released AND shipped));

ALTER TABLE stock_reservations
    ADD CONSTRAINT chk_stock_reservations_shipped_at CHECK (NOT shipped OR shipped_at IS NOT NULL);

-- Fase 7 (QUAL-02, D-94, D-95): Correlation-ID da requisição que criou o pedido e da transação
-- que gravou cada linha do outbox. As duas colunas nascem nullable, sem default e sem backfill:
-- linhas legadas do volume postgres-data ficam nulas e o código trata nulo (o relay não envia o
-- atributo; um fluxo sem HTTP gera um UUID só para o log daquele passo).

ALTER TABLE orders ADD COLUMN correlation_id VARCHAR(64);
ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(64);

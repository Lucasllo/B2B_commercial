-- Fase 7 (QUAL-02, D-94): Correlation-ID da transação que gravou cada linha do outbox do
-- inventory-service. A coluna nasce nullable, sem default e sem backfill: linhas legadas do
-- volume postgres-data ficam nulas e o código trata nulo (o relay não envia o atributo; um fluxo
-- sem ID válido gera um UUID só para o log daquele passo).

ALTER TABLE outbox_event ADD COLUMN correlation_id VARCHAR(64);

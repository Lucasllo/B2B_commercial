-- Cria as tabelas companies e users no schema auth (D-01: uma instância Postgres, um schema por
-- serviço). credit_limit em NUMERIC(19,2) trava a representação monetária desde a primeira
-- migração (D-06) — nunca inteiro em centavos.
CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    credit_limit NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('BUYER', 'SELLER_ADMIN')),
    company_id UUID REFERENCES companies(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_company_id ON users(company_id);

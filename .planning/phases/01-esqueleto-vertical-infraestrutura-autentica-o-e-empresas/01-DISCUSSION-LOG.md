# Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-16
**Phase:** 1-Esqueleto Vertical — Infraestrutura, Autenticação e Empresas
**Areas discussed:** Topologia do Postgres, JWT: chaves e validação, Representação monetária, Bootstrap do primeiro SELLER_ADMIN

---

## Topologia do Postgres

| Option | Description | Selected |
|--------|-------------|----------|
| 1 instância, schemas separados | Um container Postgres no docker-compose, um schema por serviço. Menos RAM/containers, ainda isola logicamente por serviço. | ✓ |
| 1 container por serviço | 4 containers Postgres distintos, um por serviço. Mais literal para "database per service", custa mais RAM local. | |
| 1 instância, 1 database por serviço | Mesmo container Postgres, bancos separados em vez de schemas — meio-termo. | |

**User's choice:** 1 instância, schemas separados
**Notes:** Nenhuma ressalva adicional.

---

## JWT: chaves e validação

| Option | Description | Selected |
|--------|-------------|----------|
| Par de chaves fixo no repo (dev) | Chave RSA gerada uma vez e commitada/.env git-ignorado. | |
| Gerada no startup + JWKS endpoint | auth-service gera o par RSA no boot e expõe a chave pública via JWKS; demais serviços buscam e cacheiam. | ✓ |

**User's choice:** Gerada no startup + JWKS endpoint
**Notes:** Levantada tensão com o critério de sucesso "sem chamada em tempo de execução ao auth-service" — pergunta de esclarecimento feita a seguir.

| Option | Description | Selected |
|--------|-------------|----------|
| JWKS com cache é aceitável | O critério fala de validação por requisição, sem chamada a cada token — buscar/cachear a chave no boot ou periodicamente está dentro do espírito da regra. | ✓ |
| Zero dependência de rede, mesmo no boot | Cada serviço deve validar mesmo se o auth-service estiver fora do ar — chave fixa distribuída por arquivo/config. | |

**User's choice:** JWKS com cache é aceitável
**Notes:** Esta interpretação foi registrada em CONTEXT.md (D-03) para orientar verificação do critério de sucesso na Fase 1.

| Option | Description | Selected |
|--------|-------------|----------|
| Só access token, TTL curto-médio (ex: 1h) | Sem refresh token — mais simples para um projeto de portfólio. | ✓ |
| Access token + refresh token | Adiciona um segundo fluxo de renovação, mais estado a gerenciar. | |

**User's choice:** Só access token, TTL curto-médio (ex: 1h)
**Notes:** Nenhuma ressalva adicional.

| Option | Description | Selected |
|--------|-------------|----------|
| Gateway só roteia, serviços validam | Alinhado ao AUTH-03. Gateway fica simples. | ✓ |
| Gateway valida + serviços validam de novo | Defesa em profundidade, duplica configuração de segurança. | |

**User's choice:** Gateway só roteia, serviços validam
**Notes:** Nenhuma ressalva adicional.

---

## Representação monetária

| Option | Description | Selected |
|--------|-------------|----------|
| BigDecimal com escala fixa (2 casas) | Tipo padrão Java/JPA, mapeia para NUMERIC(19,2). | ✓ |
| Inteiro em centavos (long) | Evita arredondamento manual, mas exige conversão explícita e não usa o suporte nativo do JPA. | |

**User's choice:** BigDecimal com escala fixa (2 casas)
**Notes:** Decisão vale também para o total do pedido nas Fases 4/5.

---

## Bootstrap do primeiro SELLER_ADMIN

| Option | Description | Selected |
|--------|-------------|----------|
| Seed via Flyway (credenciais fixas) | Migração Flyway insere um SELLER_ADMIN com email/senha fixos (hash BCrypt) na primeira subida. | ✓ |
| CommandLineRunner + variáveis de ambiente | Cria o SELLER_ADMIN a partir de env vars se ainda não existir. | |
| Endpoint de bootstrap protegido por segredo | Endpoint REST especial de uso único, protegido por shared secret. | |

**User's choice:** Seed via Flyway (credenciais fixas)
**Notes:** Nenhuma ressalva adicional.

---

## Claude's Discretion

- Nome exato dos schemas Postgres por serviço.
- Formato exato do payload de criação de empresa + usuário BUYER vinculado (endpoint único vs dois passos).
- Credenciais exatas do SELLER_ADMIN seedado via Flyway.

## Deferred Ideas

None — discussion stayed within phase scope.

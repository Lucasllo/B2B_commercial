# Phase 1: Esqueleto Vertical — Infraestrutura, Autenticação e Empresas - Context

**Gathered:** 2026-09-16
**Status:** Ready for planning

<domain>
## Phase Boundary

Todo o sistema sobe com um único `docker-compose up` (API Gateway + auth-service + PostgreSQL + LocalStack, todas as imagens com versão fixada) e um usuário real (vendedor ou comprador) se autentica pelo API Gateway recebendo um JWT com papel (BUYER/SELLER_ADMIN) e, para compradores, o ID da empresa. As empresas compradoras e seus limites de crédito já estão persistidos, isoladas por empresa. Esta é a base que todas as fases seguintes assumem pronta — nenhuma capacidade de catálogo, estoque, pedido ou mensageria entra nesta fase.

</domain>

<decisions>
## Implementation Decisions

### Topologia do Postgres
- **D-01:** Uma única instância Postgres no `docker-compose`, com um schema separado por serviço transacional (auth, catalog, inventory, order) em vez de um container por serviço. — **Reversibility:** costly — migrar para containers/databases separados depois exigiria migração de dados e reconfiguração de connection string em todos os serviços.

### JWT — Chaves e Validação
- **D-02:** O par de chaves RSA para assinar/validar o JWT é gerado pelo auth-service no startup, com a chave pública exposta via endpoint JWKS; os demais serviços buscam e cacheiam essa chave (Spring Security OAuth2 Resource Server). — **Reversibility:** costly — trocar para um arquivo de chave fixo distribuído por config tocaria a configuração de segurança de todos os serviços.
- **D-03:** Interpretação travada do critério de sucesso "token inválido ou expirado é rejeitado com 401 sem nenhuma chamada em tempo de execução ao auth-service" (ROADMAP.md, Fase 1, critério 3): significa validação stateless por requisição (sem chamada síncrona por token validado); buscar/cachear a chave pública via JWKS no boot ou periodicamente está dentro do critério. Downstream agents (pesquisador, planejador, verificador) devem usar esta leitura ao avaliar esse critério.
- **D-04:** Apenas access token, sem refresh token. TTL curto-médio (referência: ~1h). — **Reversibility:** reversible — um fluxo de refresh pode ser adicionado depois de forma aditiva, sem quebrar o fluxo atual.
- **D-05:** O API Gateway apenas roteia (rotas estáticas por serviço) e não valida o JWT — cada serviço downstream valida localmente e de forma independente, conforme AUTH-03. — **Reversibility:** reversible — adicionar validação no Gateway depois é aditivo (defesa em profundidade), não quebra o comportamento atual.

### Representação Monetária
- **D-06:** Valores monetários (limite de crédito na Fase 1; total do pedido nas Fases 4/5) usam `BigDecimal` com escala fixa de 2 casas decimais, mapeado para `NUMERIC(19,2)` no Postgres. — **Reversibility:** one-way — trocar para inteiro em centavos depois exigiria migração de schema em toda tabela que armazena valores monetários, incluindo tabelas que ainda não existem (pedidos, Fase 4/5).

### Bootstrap do Primeiro SELLER_ADMIN
- **D-07:** O primeiro usuário SELLER_ADMIN é criado via migração Flyway do auth-service, com email/senha fixos (hash BCrypt) inseridos na primeira subida do banco. — **Reversibility:** reversible — outros mecanismos de bootstrap podem ser adicionados depois sem afetar o que já existe.

### Claude's Discretion
- Nome exato dos schemas Postgres por serviço (ex.: `auth`, `catalog`, `inventory`, `order`) — convenção a definir no planejamento.
- Formato exato do payload de criação de empresa + usuário BUYER vinculado (endpoint único vs dois passos) — decisão de design de API, não afeta arquitetura.
- Credenciais exatas do SELLER_ADMIN seedado via Flyway (email/senha) — definir no planejamento, documentar claramente por ser específico de portfólio/demo.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Stack e decisões técnicas travadas
- `.claude/CLAUDE.md` §Technology Stack — Java 21, Spring Boot 3.5.x, Spring Cloud 2025.0.x (Northfields), Spring Cloud Gateway Server WebMVC com rotas estáticas, Spring Security OAuth2 Resource Server (Nimbus JWT), Flyway, Testcontainers, exigência de `LOCALSTACK_AUTH_TOKEN` desde a LocalStack 2026.03.0.

### Escopo e critérios de aceitação da fase
- `.planning/ROADMAP.md` §Phase 1: Esqueleto Vertical — Goal, Requirements (INFRA-01, AUTH-01/02/03, COMP-01/02/03) e os 4 Success Criteria (inclui o critério 3 sobre validação sem chamada em tempo de execução, ver D-03).
- `.planning/REQUIREMENTS.md` §Authentication & Companies — texto completo de AUTH-01, AUTH-02, AUTH-03, COMP-01, COMP-02, COMP-03.

### Contexto e restrições do projeto
- `.planning/PROJECT.md` §Constraints — stack, persistência, mensageria, containerização, processo incremental.
- `.planning/PROJECT.md` §Key Decisions — decomposição em 5 microsserviços (auth, catalog, inventory, order, notification) + API Gateway; nenhum company-service dedicado (Company vive dentro do auth-service).
- `.planning/PROJECT.md` §Context — convenção de idiomas (documentação em português, código em inglês).
- `.planning/STATE.md` §Blockers/Concerns — LOCALSTACK_AUTH_TOKEN obrigatório desde o primeiro dia; fixar versões de imagem Docker (sem `latest`).

</canonical_refs>

<code_context>
## Existing Code Insights

Projeto greenfield — nenhum código-fonte existe ainda no repositório (apenas `.claude/` e `.planning/`). Não há assets reutilizáveis, padrões estabelecidos ou pontos de integração prévios: esta fase estabelece a primeira estrutura de módulos, o primeiro `docker-compose.yml` e o primeiro serviço.

</code_context>

<specifics>
## Specific Ideas

- Uma instância Postgres compartilhada com schema por serviço (não um container por serviço) — decisão explícita para manter o `docker-compose` mais leve.
- Chaves JWT via JWKS gerado no startup do auth-service, não um arquivo de chave fixo commitado.
- Sem refresh token nesta fase — apenas access token com TTL de referência ~1h.
- Gateway roteia sem validar; validação de JWT é responsabilidade exclusiva de cada serviço.
- Dinheiro sempre em `BigDecimal`/`NUMERIC(19,2)`, nunca inteiro em centavos — vale para limite de crédito agora e total de pedido nas Fases 4/5.
- Primeiro SELLER_ADMIN nasce de uma migração Flyway com credenciais fixas, sem endpoint de bootstrap dedicado.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 1-Esqueleto Vertical — Infraestrutura, Autenticação e Empresas*
*Context gathered: 2026-09-16*

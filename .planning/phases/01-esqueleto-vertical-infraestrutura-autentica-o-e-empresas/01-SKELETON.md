# Walking Skeleton — OrderFlow

**Phase:** 1
**Generated:** 2026-09-16

> Este documento é um **contrato**, não um rascunho. As decisões arquiteturais registradas aqui
> são assumidas como prontas por todas as fases seguintes (2 a 7). Alterá-las depois não é
> refatoração — é migração.

## Capability Proven End-to-End

Com um único `docker compose up -d --wait` a partir de um clone limpo, um usuário real
(o `SELLER_ADMIN` semeado) faz login pelo API Gateway em `http://localhost:8080/api/auth/login`,
recebe um JWT RS256 assinado contendo seu papel, cria uma empresa compradora com limite de
crédito e um usuário `BUYER` vinculado, e esse `BUYER` — autenticado com o próprio JWT (que
carrega o `company_id`) — consegue ler o limite de crédito da própria empresa e recebe `403`
ao tentar ler o de outra.

Esse caminho atravessa **todas as camadas do sistema**: Docker Compose → API Gateway →
auth-service (Spring MVC + Spring Security) → JPA/Hibernate → Flyway → PostgreSQL.
LocalStack sobe junto e saudável, provando o slot de infraestrutura AWS que as Fases 3 e 5
irão consumir (SQS/DynamoDB), sem que nenhum código da Fase 1 dependa dele.

## Architectural Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Linguagem / runtime | Java 21 LTS (Temurin), confirmado localmente em `21.0.10` | Baseline atual do mercado enterprise; virtual threads disponíveis para o Gateway Server WebMVC (CLAUDE.md §Technology Stack) |
| Framework | Spring Boot 3.5.16 (linha 3.5, último patch OSS) — **sujeito ao `checkpoint:decision` do plano 01-01** | CLAUDE.md escolhe explicitamente 3.5.x sobre 4.0.x para evitar a migração Jackson 3 / Jakarta EE 11; RESEARCH.md §Open Questions sinaliza que a linha 3.5 atingiu EOL OSS em 2026-06-30 e pede confirmação humana antes de travar |
| Release train | Spring Cloud 2025.0.3 "Northfields" | Único trem casado com Boot 3.5.x; misturar trens é o erro de setup mais difícil de depurar em Spring microservices (RESEARCH.md Pitfall 2) |
| Build | Maven multi-módulo (reactor): `pom.xml` pai + `gateway/` + `auth-service/` | Mantém os BOMs (Spring Boot, Spring Cloud, Testcontainers) DRY entre os 5 serviços que virão; **Maven Wrapper (`./mvnw`) commitado** porque não há Maven de sistema neste ambiente nem garantia dele no runner de CI |
| API Gateway | Spring Cloud Gateway **Server WebMVC**, rotas estáticas por serviço, **sem validação de JWT** (D-05) | Servlet-based: não arrasta Project Reactor para uma stack que é MVC + JPA em todo o resto. Sem service discovery — os 5 serviços são fixos e conhecidos no `docker-compose` |
| Camada de dados (transacional) | **Uma** instância PostgreSQL 16.x, um **schema por serviço** (`auth`, depois `catalog`, `inventory`, `order`) (D-01) | Decisão travada do usuário: `docker-compose` mais leve que um container por serviço, mantendo a separação lógica de dados por serviço |
| Migrações | Flyway, um diretório `db/migration` por serviço, versionadas (`V1__init_auth_schema.sql`, `V2__seed_seller_admin.sql`) | Auto-configurado pelo Spring Boot: roda no boot do serviço, sem passo manual de "push". Uma migração aplicada **nunca** é editada — cria-se `V3__` (RESEARCH.md Pitfall 5) |
| Autenticação | JWT RS256 auto-emitido: par de chaves RSA-2048 gerado no **startup** do auth-service, chave pública publicada em `/.well-known/jwks.json` (D-02); apenas access token, TTL ~1h, sem refresh (D-04) | Evita um IdP externo e o peso do Spring Authorization Server para um único endpoint de login de primeira parte. A chave é efêmera por design: reiniciar o auth-service invalida os tokens emitidos (tradeoff aceito, RESEARCH.md Pitfall 4) |
| Validação de JWT | Cada serviço valida **localmente e sem chamada em tempo de execução ao auth-service** (AUTH-03 + D-03). O auth-service, sendo o emissor, decodifica com a chave pública que já tem em memória; serviços das Fases 2+ consomem `jwk-set-uri` apontando para o JWKS e cacheiam a chave | D-03 trava a leitura do critério 3: "sem chamada em tempo de execução" = sem chamada síncrona por token validado; buscar/cachear a chave é permitido |
| Autorização | Papel via claim `role` → `GrantedAuthority` (`JwtAuthenticationConverter`) + `@EnableMethodSecurity`; **isolamento por empresa via bean `CompanyGuard` consultado por `@PreAuthorize`** — nunca por filtro global do Hibernate | COMP-03 exige isolamento "comprovado por teste, não apenas por convenção". Um `@Filter` esquecido devolve dados sem filtro com `200`; o guard explícito falha alto com `403` (RESEARCH.md Pitfall 6) |
| Dinheiro | `BigDecimal` escala 2 ↔ `NUMERIC(19,2)` em toda coluna monetária (D-06) | Decisão **one-way**: trocar para inteiro em centavos depois exigiria migração em toda tabela monetária, inclusive as das Fases 4/5 que ainda não existem |
| Emulação AWS | LocalStack `2026.08.x` com `SERVICES=sqs,dynamodb`, exigindo `LOCALSTACK_AUTH_TOKEN` (conta Hobby gratuita) via `.env` gitignored + `.env.example` commitado | Desde a imagem 2026.03.0 não existe mais imagem anônima; um compose sem o token quebra para todo clone futuro, não só para o autor (RESEARCH.md Pitfall 1) |
| Orquestração local | `docker compose` V2, **toda imagem com tag de patch fixa**, `healthcheck` em todo serviço + `depends_on: condition: service_healthy` | INFRA-01 exige subida saudável a partir de clone limpo; `depends_on` sem `condition` só espera "running", não "ready" (RESEARCH.md Pitfall 3) |
| Layout de diretórios | Raiz do reactor + um módulo por serviço; dentro do módulo, pacote raiz `com.orderflow.<serviço>` com **sub-pacotes por feature** (`config`, `auth`, `company`, `user`), não por camada | `auth-service` vira o analog canônico das Fases 2+: catalog/inventory/order/notification devem espelhar esta estrutura (01-PATTERNS.md §Shared Patterns) |
| Testes | JUnit 5 + `spring-boot-starter-test` (MockMvc, AssertJ, Mockito) + Testcontainers `postgresql`. Integração em `*IT.java` (failsafe), unitários em `*Test.java` (surefire) | Toda fase entrega seus próprios testes; a Fase 7 fecha lacunas, não inicia a cobertura (REQUIREMENTS.md, nota sobre requisitos transversais) |

## Stack Touched in Phase 1

- [ ] **Project scaffold** — reactor Maven (`pom.xml` + `gateway/pom.xml` + `auth-service/pom.xml`), `./mvnw` commitado, Dockerfiles multi-stage, `.gitignore`
- [ ] **Routing** — rota estática real no Gateway: `/api/auth/**` e `/api/companies/**` → `http://auth-service:8081`
- [ ] **Database — leitura real** — `CustomUserDetailsService` carrega o `User` por email no login; `CompanyRepository` lê a empresa no endpoint de limite de crédito
- [ ] **Database — escrita real** — `POST /companies` grava `companies` + `users` numa transação; `PUT .../credit-limit` atualiza o `NUMERIC(19,2)`
- [ ] **Interação real ponta a ponta** — `POST http://localhost:8080/api/auth/login` devolve um JWT RS256 verificável; o restante do fluxo B2B usa esse token no header `Authorization`
- [ ] **Deployment** — comando local documentado que exercita a stack inteira: `docker compose up -d --wait` (falha se qualquer serviço não ficar `healthy`)

## Out of Scope (Deferred to Later Slices)

Explicitamente **fora** do esqueleto. Esta lista existe para impedir que fases futuras
reabram a discussão sobre o minimalismo da Fase 1.

- Refresh token, rotação de chave RSA, revogação/blacklist de token (D-04 trava só access token)
- Validação de JWT no Gateway (D-05: o Gateway só roteia; defesa em profundidade é aditiva depois)
- Service discovery (Eureka/Consul) e config server — os 5 serviços são fixos e conhecidos
- Rate limiting no `/auth/login` — é `GATE-01`, requisito v2
- Qualquer uso de SQS ou DynamoDB por código de aplicação — LocalStack sobe saudável na Fase 1, mas só é consumido na Fase 3
- Catálogo, estoque, pedido, saga, outbox, notificações — Fases 2 a 6
- Pipeline de CI, Correlation-ID, ADRs e OpenAPI consolidados — Fase 7 (`springdoc` pode entrar por serviço conforme nascem, mas não é critério da Fase 1)
- Auto-registro de usuários, recuperação de senha, verificação de email — não há requisito v1
- Múltiplos vendedores (marketplace) — fora de escopo do projeto inteiro (REQUIREMENTS.md §Out of Scope)

## Subsequent Slice Plan

Cada fase seguinte adiciona uma fatia vertical sobre este esqueleto **sem alterar** suas
decisões arquiteturais:

- **Fase 2 — Catálogo e Estoque:** `catalog-service` e `inventory-service` nascem espelhando a estrutura de `auth-service`, cada um com seu schema Postgres (D-01), validando JWT via `jwk-set-uri` apontando para o JWKS da Fase 1, roteados por novas rotas estáticas no mesmo Gateway.
- **Fase 3 — Primeira integração assíncrona:** `notification-service` passa a consumir o LocalStack que já sobe saudável desde a Fase 1 (SQS → DynamoDB), sem tocar no modelo de autenticação.
- **Fase 4 — Núcleo do Pedido:** `order-service` usa o claim `company_id` e o `CompanyGuard` estabelecidos aqui para escopar pedidos por empresa (ORD-08), e o `BigDecimal`/`NUMERIC(19,2)` da Fase 1 para o total do pedido.
- **Fase 5 — Saga de reserva:** Transactional Outbox dentro dos schemas Postgres já existentes; nenhuma mudança na topologia de banco decidida aqui.
- **Fase 6 — Ciclo de vida completo:** transições de status e histórico, sobre os mesmos serviços.
- **Fase 7 — Endurecimento:** OpenAPI por serviço, Correlation-ID propagado a partir do Gateway (que já existe e já é o único ponto de entrada), CI e ADRs — incluindo o ADR que registra a decisão de versão tomada no plano 01-01.

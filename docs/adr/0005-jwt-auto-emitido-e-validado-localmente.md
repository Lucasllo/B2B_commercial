---
status: Aceito
date: 2026-10-01
decision-makers: Lucas Lopes
---

# 0005 — JWT auto-emitido pelo auth-service e validado localmente pelos resource servers

## Contexto e problema

Todos os serviços de negócio (catalog, inventory, order, notification, além do próprio auth) precisam
saber quem está chamando e com qual papel (`BUYER` ou `SELLER_ADMIN`) e, para compradores, de qual
empresa. Um critério de sucesso do projeto exige que um token inválido ou expirado seja rejeitado com
401 sem nenhuma chamada em tempo de execução ao auth-service. É preciso, portanto, uma forma de
autenticação que cada serviço valide sozinho, sem depender de uma ida ao auth-service por requisição.

Também não se quer introduzir um provedor de identidade externo só para um portfólio, nem tornar o
auth-service um ponto de falha de todo o tráfego.

## Fatores de decisão

- Validação stateless por requisição: nenhuma chamada síncrona ao auth-service por token validado.
- O papel e a empresa viajam no próprio token.
- Superfície pequena: um único endpoint de login de primeira parte, sem fluxos OAuth2 completos.
- Segurança correta nas pontas: a chave pública vem do emissor e o claim `iss` é conferido.

## Alternativas consideradas

- **JWT assinado com RSA pelo auth-service, chave pública exposta em JWKS, validação local nos
  resource servers (Spring Security + Nimbus)** — **escolhida**.
- **Provedor de identidade externo** — **rejeitada**: o projeto não precisa de um IdP externo e
  ele traria um componente de infraestrutura a mais para um único endpoint de login.
- **Spring Authorization Server completo** — **rejeitada**: é a ferramenta certa para emitir tokens
  em produção, mas traz registro de clientes, telas de consentimento e todos os grants OAuth2, um
  excesso para um login de primeira parte.
- **Introspecção do token por chamada ao auth-service** — **rejeitada**: contradiz o critério de
  sucesso, que proíbe a chamada ao auth-service em tempo de execução a cada validação, e tornaria o
  auth-service um ponto único de falha.
- **jjwt em vez do suporte do Spring Security** — **rejeitada**: obrigaria a manter duas bibliotecas
  JWT, uma para emitir e outra para validar; o suporte nativo do Spring Security cobre os dois lados.

## Decisão

O auth-service gera um par de chaves RSA no startup e assina os tokens; a chave pública é exposta
no endpoint JWKS (D-02). Cada resource server configura `jwk-set-uri`, busca e cacheia a chave, e valida
assinatura, expiração e o emissor (`issuer-uri` junto de `jwk-set-uri`, de modo que o `iss` é conferido
mesmo sem descoberta OIDC). A leitura travada do critério é que buscar a chave por JWKS no boot ou
periodicamente está dentro do critério: o que não existe é chamada ao auth-service por token (D-03).

Há apenas access token, sem refresh token, com TTL de cerca de 1 hora (D-04). O API Gateway não valida
o JWT: ele só roteia, e cada serviço valida de forma independente (D-05; ver ADR 0004 e ADR 0009).

### Consequências

- Bom: login e validação desacoplados; o auth-service fora do ar não derruba requisições com token
  ainda válido.
- Bom: os testes usam o decoder de produção (e não um decoder trocado), o que fecha a checagem de
  `iss` (decisão-chave registrada no `PROJECT.md`).
- Ruim: sem refresh token, quando o token expira o usuário precisa fazer login de novo; um fluxo de
  refresh pode ser adicionado depois de forma aditiva, sem quebrar o atual.
- Ruim: o par de chaves nasce no startup do auth-service, então reiniciar o auth-service invalida os
  tokens emitidos antes; trocar para uma chave fixa distribuída por configuração tocaria a segurança de
  todos os serviços.
- Ruim: um token roubado vale até expirar, porque não há revogação centralizada.
- Validar também no Gateway como defesa em profundidade é aditivo e não faz parte desta decisão.

## Prós e contras das alternativas

### JWT local com JWKS

- Bom: sem chamada por requisição, simples de testar, usa só o suporte do Spring Security.
- Ruim: sem revogação e sem refresh token.

### Provedor de identidade externo

- Bom: login e gestão de usuários prontos e padronizados.
- Ruim: infraestrutura a mais sem necessidade neste escopo.

### Spring Authorization Server completo

- Bom: o caminho correto e completo para emitir tokens em produção.
- Ruim: superfície grande demais para um único login de primeira parte.

### Introspecção por chamada

- Bom: permite revogação imediata.
- Ruim: viola o critério de sucesso e acopla toda requisição ao auth-service.

### jjwt

- Bom: controle manual da criação do token.
- Ruim: segunda biblioteca JWT para manter ao lado da validação do Spring Security.

## Mais informações

Fase de origem: Fase 1 (esqueleto vertical, infraestrutura e autenticação).

- Decisões: D-02, D-03, D-04 e D-05 em
  [01-CONTEXT.md](../../.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-CONTEXT.md).
- Base das alternativas: [01-RESEARCH.md](../../.planning/phases/01-esqueleto-vertical-infraestrutura-autentica-o-e-empresas/01-RESEARCH.md)
  (Authorization Server completo) e a tabela de stack do [CLAUDE.md](../../.claude/CLAUDE.md) (jjwt).
- Decisão-chave sobre a validação do `iss`: [PROJECT.md](../../.planning/PROJECT.md), Key Decisions.
- Código no auth-service:
  [JwtIssuerConfig.java](../../auth-service/src/main/java/com/orderflow/auth/config/JwtIssuerConfig.java)
  e [JwksController.java](../../auth-service/src/main/java/com/orderflow/auth/config/JwksController.java).
- Código nos resource servers:
  [SecurityConfig.java do order-service](../../order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java)
  e [SecurityConfig.java do catalog-service](../../catalog-service/src/main/java/com/orderflow/catalog/config/SecurityConfig.java).

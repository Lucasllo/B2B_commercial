---
phase: 03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es
fixed_at: 2026-09-24T22:55:02Z
review_path: .planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-REVIEW.md
iteration: 1
findings_in_scope: 7
fixed: 6
skipped: 1
status: partial
---

# Fase 03: Relatório de Correção do Code Review

**Corrigido em:** 2026-09-24T22:55:02Z
**Review de origem:** .planning/phases/03-primeira-integra-o-ass-ncrona-hist-rico-de-notifica-es/03-REVIEW.md
**Iteração:** 1

**Resumo:**
- Achados no escopo (WR-01..WR-07, `fix_scope=critical_warning`): 7
- Corrigidos: 6
- Já corrigidos antes desta execução (não recontados como "fixed" nesta rodada): 1 (WR-07)
- Ignorados: 0

## Ambiente de execução e verificação

Todo o trabalho foi feito num worktree git isolado
(`.claude/worktrees/rf-03-1516-1790289597`, branch temporária
`gsd-reviewfix/03-1516`), depois integrado em `master` por fast-forward. As
verificações abaixo (compilação, testes unitários, checagem de sintaxe do
shell) rodaram **dentro desse worktree**, não no checkout principal — os
números são reproduzíveis a partir do próprio worktree enquanto ele existir,
mas o worktree é removido ao final desta execução (ver `setup_worktree`).
Depois do fast-forward, o mesmo código (idêntico, mesmos commits) está em
`master` no checkout principal.

Verificações executadas nesta rodada:
- `./mvnw -pl notification-service -am test-compile` — OK, sem erros.
- `./mvnw -pl inventory-service -am compile` e `test-compile` — OK, sem erros.
- `./mvnw -pl inventory-service,notification-service -am test` (Surefire, só
  testes unitários `*Test`, sem `*IT`) — todos passaram (exit 0).
- `bash -n scripts/smoke-notification-flow.sh` — sintaxe válida.
- Inspeção manual do bytecode resolvido de `spring-cloud-aws-autoconfigure`
  3.4.2 (via `unzip -l` + `javap`) para confirmar que
  `io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer` existe nesta
  versão exata antes de usá-la em WR-03.

**Não executado nesta rodada:** as suítes de integração (`*IT`, Testcontainers
+ LocalStack) do `inventory-service` e do `notification-service`. Conforme
orientação do orquestrador, isso exigiria subir o LocalStack (sessão Hobby de
token único) e não havia stack docker compose ativa a preservar — mas a
suíte de IT é longa e o objetivo desta rodada foi verificação por
compilação + testes unitários. `./mvnw ... test-compile` confirmou que
nenhuma classe de teste (incluindo todas as `*IT`) quebrou com as mudanças de
assinatura (`StockAdjustmentResult`, `StockAdjustedEvent.of`,
`StockEventPublisher.publishStockAdjusted`).

## Correções aplicadas

### WR-01: Contexto de `NotificationStoreUnavailableIT` mantém um listener SQS ativo que consome e descarta mensagens de outras classes de teste

**Arquivos modificados:** `notification-service/src/test/java/com/orderflow/notification/NotificationStoreUnavailableIT.java`
**Commit:** `39abf67`
**Correção aplicada:** adicionado `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)` na classe, para que o contexto (e o `@SqsListener` real que ele sobe) seja fechado ao fim da classe em vez de ficar vivo no cache de contextos do Spring Test até o fim da JVM. Também mockado `NotificationEventListener` (`@MockitoBean`), reforçando que nenhum `@SqsListener` real chegue a se registrar nesse contexto enquanto ele existir.

### WR-02: Falha permanente do DynamoDB vira laço infinito de reentrega (sem limite de tamanho do payload)

**Arquivos modificados:** `notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java`
**Commit:** `5cdf673`
**Correção aplicada:** `NotificationService.record` agora rejeita (como `InvalidNotificationEventException`, descartada sem reentrega) qualquer corpo acima de 64 KB antes do parse, cortando o cenário descrito onde um campo extra grande passa pela validação e só falha permanentemente no `putItem` do DynamoDB (item acima de 400 KB), reentregando para sempre. A parte opcional da sugestão do review (converter `DynamoDbException` 4xx em `InvalidNotificationEventException` no listener) não foi implementada — ficou fora do escopo mínimo desta correção.

### WR-03: Publicação síncrona no SQS roda na thread da requisição HTTP sem timeout

**Arquivos modificados:** `inventory-service/src/main/java/com/orderflow/inventory/config/SqsMessagingConfig.java`
**Commit:** `01e8a59`
**Correção aplicada:** adicionado um bean `SqsAsyncClientCustomizer` que limita `apiCallTimeout` a 3s e `apiCallAttemptTimeout` a 1s no `SqsAsyncClient` usado pelo `SqsTemplate`, em vez do timeout padrão de ~30s + retries. Antes de aplicar, confirmei via inspeção do jar resolvido (`spring-cloud-aws-autoconfigure` 3.4.2) que a classe `io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer` existe nessa versão exata e que `SqsAsyncClientBuilder` (via `SdkClientBuilder`) expõe `overrideConfiguration(Consumer<ClientOverrideConfiguration.Builder>)` — o código sugerido pelo review compila sem ajuste.

### WR-04: `occurredAt` é gerado depois do commit, na publicação, e pode inverter a ordem cronológica do histórico sob concorrência

**Arquivos modificados:**
`inventory-service/src/main/java/com/orderflow/inventory/stock/StockAdjustmentResult.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryService.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/dto/StockAdjustedEvent.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/messaging/StockEventPublisher.java`,
`inventory-service/src/main/java/com/orderflow/inventory/stock/InventoryController.java`
**Commit:** `274959d`
**Status:** `fixed: requires human verification` (achado classificado como lógica de concorrência/ordenação — verificado apenas por compilação e testes unitários; nenhum teste de concorrência real com Testcontainers/LocalStack foi executado nesta rodada)
**Correção aplicada:** `StockAdjustmentResult` ganhou o campo `adjustedAt`, capturado em `InventoryService.setStock` logo após o `saveAndFlush` — ainda dentro da tentativa transacional que gravou o ajuste — em vez de `StockAdjustedEvent.of` chamar `Instant.now()` no momento da publicação (fora da transação, depois do commit). `InventoryController` repassa `result.adjustedAt()` para `StockEventPublisher.publishStockAdjusted`, que agora recebe o instante em vez de gerar um novo. Nenhum teste construía `StockAdjustmentResult`/`StockAdjustedEvent` diretamente (todos os testes existentes passam pela API REST), então não foi necessário atualizar assinaturas em testes — confirmado por `test-compile` limpo.
**Recomendação de verificação manual:** rodar `StockAdjustedEventPublishingIT` (já existente, não alterado) contra o LocalStack real e, se possível, um teste de concorrência dedicado com dois `PUT` simultâneos no mesmo produto para confirmar que a ordem de `occurredAt` no histórico bate com a ordem real de commit.

### WR-05: Smoke aborta em silêncio (sem mensagem `SMOKE FALHOU`) quando `grep` não encontra nada, por causa de `set -o pipefail`

**Arquivos modificados:** `scripts/smoke-notification-flow.sh`
**Commit:** `27e675c`
**Correção aplicada:** `count_history_entries` agora envolve o `grep -o` com `{ ... || true; }`, evitando que uma resposta sem `"recordedAt"` (lista vazia ou corpo de erro) derrube o script via `set -e` sem imprimir a mensagem `fail "..."`. As chamadas `curl` dentro dos laços de polling dos passos 5 e 7 também ganharam `|| true`, para que um erro de conexão transitório do curl vire uma iteração vazia (o laço tenta de novo no próximo segundo) em vez de matar o script.

### WR-06: `sanitizeForLog` não neutraliza "qualquer caractere de controle", como o javadoc afirma

**Arquivos modificados:**
`notification-service/src/main/java/com/orderflow/notification/history/NotificationService.java`,
`notification-service/src/test/java/com/orderflow/notification/history/NotificationServiceTest.java`
**Commit:** `14ee2db`
**Correção aplicada:** o regex de `sanitizeForLog` passou de `[\r\n\t]` para `[\p{Cntrl}\u0085\u2028\u2029]` (usando escapes `\u` no código-fonte, não os caracteres reais), cobrindo qualquer caractere de controle Unicode, NEL (U+0085) e os separadores de linha/parágrafo Unicode U+2028/U+2029. Adicionado um novo teste parametrizado (`unsupportedEventTypeWithAnsiEscapeAndUnicodeLineSeparatorProducesSanitizedMessage`) cobrindo ESC (codepoint 27) e o separador de linha Unicode (codepoint 8232), construídos via `(char) codepoint` em vez de escapes `\u` no fonte para não embutir caracteres invisíveis reais no arquivo `.java`.

### WR-07: O resource server de produção não valida o emissor (`iss`) — já corrigido antes desta execução

**Status:** já corrigido (não é um "fixed" desta rodada — nenhuma alteração foi feita agora)
**Commit de origem da correção:** `eac39a8` (`fix(quick-260923-tj9): inventory-service valida iss do JWT via decoder de producao`)
**Verificação:** confirmei que `notification-service/src/main/resources/application.yml` já declara `issuer-uri` (linha 37, com o mesmo valor `orderflow-auth-service` emitido pelo `auth-service`) e que `notification-service/src/test/java/com/orderflow/notification/support/TestJwt.java` já usa `JwkSetUriJwtDecoderBuilderCustomizer` em vez de um `JwtDecoder` próprio, deixando o validador de emissor vir da configuração de produção (comentário explícito referenciando WR-07 no próprio arquivo). Nenhuma ação adicional foi necessária.

## Achados fora de escopo

Os achados `IN-01` a `IN-07` (Info) estão fora do escopo desta execução
(`fix_scope=critical_warning`) e não foram tocados.

---

_Corrigido em: 2026-09-24T22:55:02Z_
_Corretor: Claude (gsd-code-fixer)_
_Iteração: 1_

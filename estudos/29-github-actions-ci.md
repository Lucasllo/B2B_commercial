# GitHub Actions e a integração contínua (CI)

Arquivos desta parte (plano 07-11):

- `.github/workflows/ci.yml` — o workflow
- `scripts/check-no-skipped-tests.sh` — guarda contra teste desabilitado em silêncio
- `scripts/ci-summary.sh` — resumo dos relatórios de teste para a tela do run
- `scripts/smoke-correlation-id.sh` — o smoke de rastreabilidade (roda na stack local, não no CI)

Continuação de [24-scripts-smoke.md](24-scripts-smoke.md) (os smokes rodam na stack local) e de
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md) (o ID que o smoke persegue pelos logs).

## O que é CI, em uma frase

**Integração contínua** é um robô que, a cada `git push`, baixa o código numa máquina limpa,
compila e roda todos os testes, e avisa se algo quebrou. A máquina é limpa de propósito: se o
projeto só funciona na sua máquina porque você tem uma variável de ambiente esquecida, o robô
descobre na hora. Para um portfólio, o selo verde na aba **Actions** do GitHub é a prova mais
barata de que "builda e testa" não é só uma frase do README.

## Como um workflow do GitHub Actions é organizado

Um arquivo YAML em `.github/workflows/` descreve tudo:

- **Gatilhos (`on:`)** — quando o workflow roda. Aqui: `push` (qualquer branch) e
  `pull_request`.
- **Jobs (`jobs:`)** — blocos que rodam, por padrão, **em paralelo**, cada um numa máquina
  virtual própria (o *runner*). Aqui: `guardas`, `sem-localstack`, `com-localstack` e `e2e`.
- **Passos (`steps:`)** — o que acontece dentro de um job, em ordem: baixar o código
  (`actions/checkout`), instalar o Java (`actions/setup-java`), rodar `./mvnw verify`...
- **Matriz (`strategy.matrix`)** — o mesmo job repetido para uma lista de valores. A matriz
  `module: [auth-service, catalog-service, gateway]` cria três "pernas", uma por serviço, e o
  resumo do run mostra qual perna ficou vermelha. Isso é o "falha visível" do D-104: quem
  quebrou aparece pelo nome.
- **`needs:`** — dependência entre jobs. O `e2e` declara `needs: [sem-localstack,
  com-localstack]`, então só roda depois de todas as pernas e fica *skipped* se alguma falhar.
- **Secrets** — valores guardados no GitHub (Settings → Secrets and variables → Actions) e
  lidos com `${{ secrets.NOME }}`. O GitHub mascara o valor nos logs.

Escolhas pequenas, mas deliberadas:

- `runs-on: ubuntu-24.04` fixo, nunca `ubuntu-latest`. O `latest` começa a migrar para
  Ubuntu 26.04 a partir de 2026-10-19; uma troca de imagem no meio do projeto poderia
  quebrar o CI sem que nada no código mudasse.
- `actions/setup-java` com `distribution: temurin`, `java-version: '21'` e `cache: maven` — o
  mesmo JDK do projeto e o repositório Maven em cache entre runs.
- `permissions: contents: read` — o token interno do workflow só lê o código.
- `concurrency` por branch com `cancel-in-progress: true` — um push novo na mesma branch
  cancela o run velho em vez de empilhar.
- Só ações da organização `actions/` (checkout, setup-java, upload-artifact). Ações de
  terceiros são código de outras pessoas rodando com acesso ao seu repositório
  (*supply-chain*); aqui não há nenhuma necessidade delas (T-07-38).

## Por que existem jobs "sem LocalStack" e "com LocalStack" (D-101)

Desde 2026-03-23 o LocalStack exige um **auth token** até no plano gratuito (Hobby). A
documentação não diz com clareza se o mesmo token aceita **várias sessões simultâneas** — e
cada módulo com SQS/DynamoDB sobe o seu próprio container LocalStack nos testes de integração
(Testcontainers). Como a suposição não foi confirmada (A2), o desenho é conservador:

| Job | Módulos | Execução |
|---|---|---|
| `sem-localstack` | auth-service, catalog-service, gateway | em paralelo (só Postgres ou nenhum container) |
| `com-localstack` | inventory-service, order-service, notification-service | `max-parallel: 1` — uma perna por vez |
| `e2e` | e2e-tests | depois dos dois jobs acima |

**Como liberar o paralelismo depois (Open Question 1 do 07-RESEARCH.md).** Teste de dois
minutos: com o mesmo token no `.env`, subir dois containers LocalStack ao mesmo tempo
(`docker run` duas vezes com portas diferentes e `LOCALSTACK_AUTH_TOKEN`). Se os dois ficarem
saudáveis, o limite não existe e dá para trocar `max-parallel: 1` por paralelo total — o
ganho é só tempo, não correção.

### Push e pull request do mesmo repositório

Com `push` em qualquer branch **e** `pull_request`, um PR aberto de um branch do próprio
repositório dispararia dois runs simultâneos da mesma SHA, ambos subindo LocalStack com o
mesmo token — justamente o que D-101 evita. Por isso os jobs com LocalStack só rodam em
`push` ou em PR **de fork**:

```yaml
if: github.event_name == 'push' || github.event.pull_request.head.repo.full_name != github.repository
```

O PR do próprio repositório já está coberto pelo run de `push` da mesma SHA.

### O job `e2e` e o `-DskipTests`

`./mvnw -pl e2e-tests -am verify` rodaria **de novo** os testes de integração de
order-service e inventory-service (o `-am` inclui os módulos de que o e2e depende), cada um
subindo outro LocalStack. Por isso o job faz em dois passos:

1. `./mvnw -pl order-service,inventory-service -am install -DskipTests` — só instala os jars
   no repositório local;
2. `./mvnw -pl e2e-tests verify` — roda apenas os testes do e2e.

É o **único** `-DskipTests` do workflow, e não esconde nada: os testes desses módulos já
rodaram, nas pernas de `com-localstack`, que o `e2e` exige em `needs`.

## Por que o CI falha de forma visível sem o token (D-103)

Se o secret `LOCALSTACK_AUTH_TOKEN` não existir, o LocalStack nem sobe, e o erro apareceria
enterrado dentro de um stack trace do Maven. Pior seria "resolver" pulando os testes: o run
ficaria **verde sem ter provado nada**. Então o primeiro passo dos jobs com LocalStack é:

```bash
if [ -z "$LOCALSTACK_AUTH_TOKEN" ]; then
  echo "::error::Secret LOCALSTACK_AUTH_TOKEN ausente ..."
  exit 1
fi
```

`::error::` é um comando do GitHub Actions: a mensagem vira uma anotação vermelha no topo do
run. Note que o teste é `-z` (variável vazia?) e **nunca imprime o valor** (T-07-37). Não há
`continue-on-error` em lugar nenhum, nem `set -x` (que imprimiria cada comando, inclusive com
segredos expandidos).

A guarda `check-no-skipped-tests.sh`, no job `guardas`, completa a ideia: ela procura
`@Disabled`, `@EnabledIf...`, `Assumptions.assume...` e parentes em todo `src/test/java`. Um
teste desabilitado em silêncio é o mesmo problema por outro caminho (T-07-39).

## Por que PR de fork não recebe o secret (T-07-36)

Quando alguém abre um PR a partir de um **fork**, o GitHub **não** entrega os secrets do
repositório ao workflow — se entregasse, qualquer pessoa poderia abrir um PR que imprime o
token. O gatilho `pull_request_target` roda no contexto do repositório-alvo, **com** secrets,
sobre código que não é de confiança: é o erro clássico, e por isso o projeto só usa
`pull_request` e `push`. Num PR de fork, os jobs com LocalStack falham de propósito com a
anotação do D-103; os de `sem-localstack` rodam normalmente.

## Onde ver os relatórios (D-104)

Duas camadas, ambas com `if: always()` para valer também quando o build falha:

1. **Artifact** (`actions/upload-artifact`): os diretórios `surefire-reports` (testes
   unitários) e `failsafe-reports` (testes de integração, `*IT`) de cada módulo, baixáveis no
   fim da página do run (`relatorios-<módulo>`).
2. **Job summary**: `scripts/ci-summary.sh <módulo>` soma as tags `<testsuite>` dos
   `TEST-*.xml` e escreve uma tabela em markdown em `$GITHUB_STEP_SUMMARY`, a "tela de
   resumo" do run — com a lista das suítes que falharam.

Ferramentas prontas de relatório (`dorny/test-reporter` e similares) foram descartadas: exigem
a permissão `checks: write` e são ações de terceiros.

## Problemas conhecidos

- **`client version 1.32 is too old`** — se um run futuro falhar assim, a imagem do runner
  passou a trazer Docker 29 e o cliente do Testcontainers negociou uma API antiga. A correção
  é um arquivo `src/test/resources/docker-java.properties` com `api.version=1.44` nos módulos
  que usam Testcontainers. **Não foi aplicado preventivamente**: o Testcontainers 1.21.4
  (efetivo, vindo do BOM do Spring Boot) já negocia a API correta, e a imagem `ubuntu-24.04`
  traz Docker 28.
- **Token Hobby como secret do Actions (A3)** — a documentação do LocalStack fala em "CI Auth
  Token" de workspace. Se o token de desenvolvedor do `.env` for recusado no CI, gere o CI
  Auth Token na conta e cadastre esse valor no secret.
- **Teste do CI no GitHub** — o único modo de provar "builda e testa a cada push" é observar
  um run verde e um vermelho de propósito (inverter uma asserção num branch descartável).
  Isso é uma verificação humana registrada no UAT da fase.

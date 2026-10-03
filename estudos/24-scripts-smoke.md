# Pasta `scripts` — os testes de fumaça (smoke tests)

Pasta: `scripts/` na raiz do projeto.

Os **smokes** (rodam com a stack ligada):

| Arquivo | Passos | O que prova |
|---|---|---|
| `smoke-notification-flow.sh` | 7 | Um ajuste de estoque no `inventory-service` vira um registro no histórico do `notification-service`, passando só pela fila SQS |
| `smoke-order-flow.sh` | 15 | Pedidos e crédito: aprovação automática e pendente, decisão do vendedor, produto descontinuado e isolamento entre empresas |
| `smoke-order-saga.sh` | 10 | A saga de reserva: `RESERVING` → `CONFIRMED` com a reserva no inventory, ou `CANCELLED` com motivo |
| `smoke-order-lifecycle.sh` | 16 | O ciclo de vida inteiro: transportadora, expedição, entrega, baixa de estoque e linha do tempo |
| `smoke-correlation-id.sh` | 8 | O mesmo `X-Correlation-Id` nos logs do Gateway e dos cinco serviços |

As **guardas** (não precisam da stack; rodam no CI):

| Arquivo | O que confere |
|---|---|
| `check-no-skipped-tests.sh` | Nenhum teste desligado com `@Disabled`, `@EnabledIf...`, `Assumptions` e parentes |
| `check-adrs.sh` | Os 11 ADRs de `docs/adr/` seguem o formato e citam decisões que existem |
| `check-coverage-matrix.sh` | Toda regra central da matriz tem teste unitário e de integração que existem de verdade |
| `ci-summary.sh` | Não confere nada: monta a tabela de resultados de teste na tela do run do CI |

## O que é um "teste de fumaça", em uma frase

É um script que, com **o sistema inteiro ligado de verdade** (`docker compose up`), faz as
operações principais como um usuário faria e confere se as respostas estão certas.

O nome vem da eletrônica: quando alguém monta um aparelho novo, a primeira coisa que faz é
ligar na tomada e ver **se sai fumaça**. Se não sair, o básico está funcionando e vale a pena
testar o resto com calma.

## 1. Qual a diferença para os testes em Java?

O projeto já tem muitos testes em Java (ver [15-testes.md](15-testes.md)). A diferença:

| | Testes Java (`*Test`, `*IT`) | Scripts de smoke |
|---|---|---|
| O que roda | **Um serviço** por vez (no máximo dois, no módulo `e2e-tests`) | **Todos os serviços** juntos |
| Os outros serviços | Falsos (*stubs*; no `order-service`, um servidor HTTP do próprio JDK, o `DownstreamStubServer`) | **Reais** |
| Por onde entra | Direto no serviço | Pelo **Gateway**, como um usuário de verdade |
| Quando roda | `./mvnw verify`, à mão e no CI a cada push | À mão, com o `docker compose` ligado (não rodam no CI) |

Um teste Java do `order-service` finge que o `catalog-service` existe. O smoke usa o
`catalog-service` de verdade. Por isso ele pega erros que só aparecem quando as peças se
encaixam: uma rota errada no Gateway (ver [10-gateway-application-yml.md](10-gateway-application-yml.md)),
uma variável de ambiente faltando no `docker-compose.yml` (ver [01-docker-compose.md](01-docker-compose.md)),
um serviço esperando um campo JSON com outro nome.

## 2. Como rodar

Os comandos também estão no `README.md` da raiz:

```bash
docker compose up -d                          # liga tudo
bash scripts/smoke-notification-flow.sh
bash scripts/smoke-order-flow.sh
bash scripts/smoke-order-saga.sh
bash scripts/smoke-order-lifecycle.sh
bash scripts/smoke-correlation-id.sh
```

Cada script imprime o progresso (`1/7 ...`, `2/7 ...` no de notificação; `1/15 ...`, `2/15 ...`
no de pedidos, e assim por diante) e termina com uma linha começando por `SMOKE OK` se tudo
passou. Se algo falhar, para na hora com `SMOKE FALHOU: <o que deu errado>`.

Todos criam os próprios dados (empresas com e-mail único, produtos novos, estoque), então podem
rodar várias vezes na mesma base, em qualquer ordem, sem passo manual. E nenhum imprime token ou
segredo do `.env`.

## 3. O que é um arquivo `.sh`

É um **script Bash**: uma lista de comandos de terminal guardada num arquivo, para não ter que
digitar tudo toda vez. No Windows, ele roda pelo **Git Bash**.

### O começo, igual em todos os smokes

```bash
#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1

cd "$(dirname "${BASH_SOURCE[0]}")/.."
```

- **`#!/usr/bin/env bash`** (o *shebang*): avisa o sistema que o arquivo deve ser executado
  pelo Bash.
- **`set -euo pipefail`**: o "modo rigoroso" do Bash. Sem ele, o Bash **continua rodando
  mesmo depois de um erro**, o que é péssimo para um teste.
  - `-e`: se algum comando falhar, pare o script.
  - `-u`: se usar uma variável que não existe (um erro de digitação, por exemplo), pare.
  - `-o pipefail`: numa cadeia `a | b | c`, se **qualquer um** falhar, conta como falha.
    Normalmente o Bash só olharia o último.
- **`export MSYS_NO_PATHCONV=1`**: ajuste para Windows. O Git Bash tenta "ajudar"
  transformando textos como `/proc/sys/...` em caminhos do Windows
  (`C:\Program Files\Git\proc\...`). Essa linha desliga esse comportamento.
- **`cd "$(dirname ...)/.."`**: vai para a raiz do projeto, a pasta que tem o
  `docker-compose.yml`. Assim o script funciona de qualquer pasta em que você estiver.

### As funções auxiliares

```bash
fail() {
    echo "SMOKE FALHOU: $1" >&2
    exit 1
}
```

Imprime a mensagem de erro e encerra com o código `1`. No terminal, `0` quer dizer "deu certo"
e qualquer outro número quer dizer "deu errado". O `>&2` manda a mensagem para a **saída de
erro**, um canal separado da saída normal.

```bash
exec_gateway() {
    docker compose exec -T gateway "$@" | strip_cr
}
```

A função mais usada. **`docker compose exec gateway`** roda um comando **dentro do container do
Gateway**. Ou seja, o `curl` que faz as requisições HTTP não roda no seu computador, roda lá
dentro. O `"$@"` repassa todos os argumentos que a função recebeu. O `-T` desliga o "terminal
interativo", que não faz sentido dentro de um script.

```bash
strip_cr() {
    tr -d '\r'
}
```

Remove o caractere `\r` do texto. Windows e Linux terminam as linhas de jeitos diferentes (o
Windows usa `\r\n`, o Linux só `\n`). Um `\r` invisível sobrando faria `"200\r"` ser diferente
de `"200"`, e o teste falharia sem motivo aparente.

### O padrão de toda verificação

```bash
STATUS_NO_TOKEN=$(exec_gateway curl -s -o /dev/null -w '%{http_code}' \
    "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}")
[ "$STATUS_NO_TOKEN" = "401" ] || fail "GET /api/notifications/{id} sem token devolveu ${STATUS_NO_TOKEN}, esperado 401"
echo "3/7 GET sem token confirma 401"
```

1. **Faz a requisição** com `curl` e guarda o resultado numa variável. O `$( ... )` captura o
   que o comando imprimiu. No `curl`: `-s` é silencioso, `-o /dev/null` joga o corpo fora e
   `-w '%{http_code}'` imprime só o código HTTP.
2. **Confere**: `[ A = B ] || fail "..."` se lê "A é igual a B? **Se não for** (`||`),
   falhe com esta mensagem".
3. **Informa o progresso** com `echo`.

### Como ele lê o JSON

```bash
TOKEN=$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
```

O `sed` é uma ferramenta de busca e substituição de texto. Essa linha quer dizer: "procure
`"accessToken":"` e pegue tudo até a próxima aspa". É um jeito "na mão" de tirar um campo de um
JSON. O normal seria usar a ferramenta `jq`, mas ela nem sempre está instalada, e o `sed` existe
em qualquer lugar.

O `smoke-order-flow.sh` organiza isso em três funções:

- `extract_string`: pega um campo de texto (`"chave":"valor"`).
- `extract_first_string`: pega o campo **do início** do JSON. Serve quando o mesmo nome aparece
  aninhado mais adiante, como o `id` da empresa no topo e o `id` do `buyerUser` lá dentro. O
  `sed` "guloso" pegaria a última ocorrência, não a primeira.
- `extract_raw`: pega um campo numérico ou booleano, sem aspas (`"totalElements":3`).

## 4. `smoke-notification-flow.sh` (7 passos)

Prova a mensageria da Fase 3: o `inventory-service` e o `notification-service` **não se
conhecem**. Eles conversam só pela fila SQS (ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md)
e [19-sqslistener-consumo.md](19-sqslistener-consumo.md)).

| Passo | O que faz | O que prova |
|---|---|---|
| 1 | Login com o vendedor de demonstração | O `auth-service` funciona pelo Gateway |
| 2 | Gera um `productId` novo | Cada execução usa dados novos e não se confunde com execuções anteriores |
| 3 | `GET /api/notifications/{id}` **sem token**, espera **401** | A rota existe no Gateway e está protegida |
| 4 | `PUT /api/inventory/{id}` com quantidade 42, espera **200** | O ajuste de estoque funciona |
| 5 | Consulta o histórico **a cada segundo, por até 15s**, até aparecer `STOCK_ADJUSTED` com 42 | O evento atravessou a fila e chegou ao DynamoDB |
| 6 | Manda **a mesma mensagem duas vezes** direto na fila e confere **1** registro | Mensagem repetida não duplica (idempotência) |
| 7 | Faz mais dois ajustes (50 e 60) e confere **3** registros | Ajustes diferentes se acumulam |

### Por que o passo 5 tem um laço de espera?

```bash
while [ "$ELAPSED" -le 15 ]; do
    HISTORY_BODY=$(exec_gateway curl -s "${GATEWAY_URL}/api/notifications/${PRODUCT_ID}" \
        -H "Authorization: Bearer ${TOKEN}" || true)
    if printf '%s' "$HISTORY_BODY" | grep -q '"eventType":"STOCK_ADJUSTED"' \
        && printf '%s' "$HISTORY_BODY" | grep -q '"newQuantityOnHand":42'; then
        FOUND=1
        break
    fi
    sleep 1
    ELAPSED=$((SECONDS - WAIT_START))
done
```

A mensageria é **assíncrona**: o `PUT` responde 200 **antes** de o `notification-service`
processar o evento, porque a mensagem ainda está a caminho pela fila. Se o script consultasse o
histórico logo em seguida, ele estaria vazio, e o teste falharia mesmo com tudo certo.

Então o script **tenta de novo a cada segundo**, por até 15 segundos. É a mesma ideia do
Awaitility nos testes Java. O `|| true` impede que uma falha passageira do `curl` derrube o
script por causa do `set -e`: aquela tentativa só conta como "ainda não" e o laço continua.
`SECONDS` é uma variável especial do Bash que conta os segundos desde que o script começou.

### O passo 6: testando a reentrega

```bash
exec_localstack awslocal --region us-east-1 sqs send-message \
    --queue-url "$QUEUE_URL" --message-body "$REDELIVERY_BODY" >/dev/null
exec_localstack awslocal --region us-east-1 sqs send-message \
    --queue-url "$QUEUE_URL" --message-body "$REDELIVERY_BODY" >/dev/null
```

O SQS às vezes entrega a mesma mensagem **mais de uma vez**. Isso é normal e está documentado
pela AWS. Para simular, o script usa o `awslocal` (a linha de comando da AWS apontando para o
LocalStack) e coloca **a mesma mensagem duas vezes** na fila. Depois:

1. Espera a fila **esvaziar**, conferindo os atributos `ApproximateNumberOfMessages`
   (esperando na fila) e `ApproximateNumberOfMessagesNotVisible` (sendo processadas). Quando os
   dois chegam a zero, as duas entregas foram consumidas e confirmadas.
2. Confere que o histórico tem **só 1** registro. Isso prova que a gravação no DynamoDB é
   idempotente pela chave (ver [17-dynamodb.md](17-dynamodb.md)).

### A função `count_history_entries`

```bash
count_history_entries() {
    { grep -o '"recordedAt"' || true; } | wc -l | tr -d ' '
}
```

Conta quantos registros tem o histórico contando quantas vezes aparece `"recordedAt"`. Esse
campo foi escolhido porque só existe **uma vez por registro**, enquanto `eventId` e `eventType`
aparecem duas vezes (no registro e dentro do conteúdo do evento). O `|| true` faz uma lista
vazia virar "0" em vez de um erro, porque o `grep` "falha" quando não encontra nada.

## 5. `smoke-order-flow.sh` (15 passos)

Prova o fluxo de pedidos da Fase 4 com os serviços reais pelo Gateway. É exatamente o que
[21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md) e
[22-services-de-pedido.md](22-services-de-pedido.md) explicam, só que testado de fora.

Desde a Fase 5 (plano 05-06), todo pedido aprovado — automática ou manualmente — sai da
aprovação já em **`RESERVING`**: a saga de reserva de estoque começa na mesma hora (ver
[26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)). O script foi ajustado para
isso: os passos 5 e 11 esperam `RESERVING`. A regra de crédito não muda, porque `RESERVING`
também consome crédito.

Esse ajuste trouxe um detalhe: o passo 3 agora **põe estoque** no P1. Sem estoque, os pedidos
aprovados terminariam `CANCELLED` pela saga, o crédito voltaria e as contas dos passos seguintes
deixariam de bater.

### A preparação (passos 1 a 3)

1. **Login do vendedor** e leitura do `userId` dele via `GET /api/auth/me`. Esse id vai ser
   conferido no passo 11.
2. **Cria duas empresas, A e B**, cada uma com **limite de R$ 1.000** e um comprador. Os e-mails
   levam um sufixo único (`buyer-a-<uuid>@orderflow.local`), para o script poder rodar várias
   vezes na mesma base sem dar "e-mail já existe".
3. **Cria dois produtos**, P1 a R$ 100 (com estoque 100) e P2 a R$ 50, e **descontinua o P2**.

### Os testes (passos 4 a 15)

| Passo | Quem | Faz | Espera | Prova |
|---|---|---|---|---|
| 4 | ninguém | Cria pedido **sem token** | 401 | Rota protegida |
| 5 | Comprador A | 4 × P1 = **R$ 400** | `RESERVING`, `decidedBy=SYSTEM` | Aprovação automática (0 + 400 ≤ 1000) |
| 6 | Comprador A | 7 × P1 = **R$ 700** | `PENDING_APPROVAL` | Estourou o limite (400 + 700 > 1000) |
| 7 | Comprador A | Pede o **P2 descontinuado** | 422 com o id do P2 | O catálogo real recusa o produto |
| 8 | Vendedor | Tenta **criar** pedido | 403 | Só comprador cria pedido |
| 9 | Vendedor | Lista os `PENDING_APPROVAL` | Contém o pedido do passo 6 | A fila de aprovação funciona |
| 10 | Comprador A | Tenta **aprovar o próprio** pedido | 403 | Só vendedor decide |
| 11 | Vendedor | **Aprova** o pedido de R$ 700 | `RESERVING`, `decidedBy` = id do vendedor | Aprovação manual com registro de quem aprovou |
| 12 | Comprador A | 1 × P1 = **R$ 100** | `PENDING_APPROVAL` | Os R$ 700 aprovados manualmente **contam** na exposição (1100 + 100 > 1000) |
| 13 | Vendedor | Rejeita **sem corpo**, depois **com motivo**, depois tenta **aprovar** | 400, `REJECTED`, 409 | Rejeição exige um corpo com motivo; pedido já decidido não pode ser decidido de novo |
| 14 | Comprador B | Abre o pedido da empresa A e lista os próprios | 404 e `totalElements=0` | **Isolamento**: B não vê nada de A, nem que o pedido existe |
| 15 | Comprador A e vendedor | A lista os próprios; vendedor abre o pedido do passo 5 | `totalElements=3` e 200 | A vê só os dele; o vendedor vê tudo |

Os valores foram **escolhidos a dedo** para testar a fronteira do limite: R$ 400 cabe, R$ 700 a
mais não cabe, e depois da aprovação manual até R$ 100 já não cabe.

### A função `do_request`: uma requisição, duas informações

```bash
do_request() {
    local out
    out=$(exec_gateway curl -s -w '\n%{http_code}' "$@")
    REQ_STATUS=$(printf '%s' "$out" | tail -n1)
    REQ_BODY=$(printf '%s' "$out" | sed '$d')
}
```

No passo 7, o script precisa conferir **o código HTTP** (422) **e o corpo** (se contém
`invalid_order_items`). A solução ingênua seria fazer a requisição duas vezes. Mas um `POST`
**cria coisas**: repetir poderia criar um pedido a mais e bagunçar a contagem do passo 15.

Então a função faz **uma** requisição e pede ao `curl` para imprimir o corpo e, numa última
linha, o código. Depois separa: `tail -n1` pega a última linha (o código) e `sed '$d'` apaga a
última linha (sobra o corpo). `local` quer dizer que a variável `out` só existe dentro da
função. Os smokes mais novos copiam essa mesma função.

## 6. `smoke-order-saga.sh` (10 passos)

Prova o Core Value do projeto (plano 05-06): a saga de reserva de estoque entre order-service e
inventory-service, pela fila, com tudo real.

| Passo | O que faz | O que prova |
|---|---|---|
| 1–3 | Login do vendedor, empresa com limite R$ 1.000, produtos P1 (estoque 10), P2 (sem estoque) e P3 (estoque 50) | Preparação com dados novos |
| 4–5 | 3 × P1: nasce `RESERVING` e, esperando, chega a `CONFIRMED` | A saga de sucesso fecha sozinha |
| 6 | Consulta o estoque de P1: `onHand=10`, `reserved=3`, `available=7` | A reserva ficou gravada no inventory |
| 7 | 20 × P1: `RESERVING` → `CANCELLED` com `INSUFFICIENT_STOCK`; reservado de P1 continua 3 | A falha não mexe no que já estava reservado |
| 8 | 1 × P2: `CANCELLED` com `PRODUCT_NOT_STOCKED` | Produto sem linha de estoque é tratado |
| 9 | 11 × P3 (R$ 1.100): `PENDING_APPROVAL` → vendedor aprova → `RESERVING` → `CONFIRMED` | A aprovação manual também passa pela saga |
| 10 | O ajuste de estoque do passo 3 aparece no histórico de notificações | O `STOCK_ADJUSTED` agora sai pelo outbox |

Os passos 5 e 7 usam o mesmo laço de espera do passo 5 do smoke de notificação: o `POST` devolve
`RESERVING` na hora, e o resultado chega segundos depois pela fila.

## 7. `smoke-order-lifecycle.sh` (16 passos)

Prova o ciclo de vida completo da Fase 6 (plano 06-06), com os **seis** módulos envolvidos:
nascimento, confirmação com transportadora, expedição, entrega, baixa de estoque e linha do tempo
lida pelo comprador.

| Passo | O que faz | O que prova |
|---|---|---|
| 1–3 | Vendedor, empresas A e B, produto P1 a R$ 10 com estoque 10 | Preparação |
| 4–5 | 3 × P1: `RESERVING` → `CONFIRMED` com `carrier` preenchido e `trackingCode` no padrão `^[A-Z]{2}[0-9]{9}BR$` | Transportadora simulada atribuída na confirmação |
| 6 | Comprador tenta `POST /ship` | 403: só o vendedor expede |
| 7 | `deliver` num pedido `CONFIRMED` | 409 `invalid_order_transition`: não dá para pular etapa |
| 8 | Vendedor expede | `SHIPPED`, com quem expediu |
| 9 | Espera o estoque de P1 virar `onHand=7`, `reserved=0` | A baixa física chegou ao inventory pela fila (`ShipStock`) |
| 10 | `ship` de novo | 409: não expede duas vezes |
| 11 | Vendedor entrega | `DELIVERED` |
| 12 | Comprador A lê a linha do tempo | Exatamente `ORDER_CREATED ORDER_APPROVED ORDER_CONFIRMED ORDER_SHIPPED ORDER_DELIVERED`, com transportadora e rastreio na mensagem |
| 13 | Comprador B lê a mesma linha do tempo | 404 `order_not_found`: isolamento entre empresas |
| 14 | 20 × P1: `CANCELLED` com `INSUFFICIENT_STOCK`; `ship` recusado | O caminho triste também fica na linha do tempo |
| 15 | 100 × P1 (acima do limite): `PENDING_APPROVAL` → `REJECTED` | A rejeição também fica na linha do tempo |
| 16 | — | Fim: ciclo completo demonstrado |

Um cuidado do passo 5: o script confere o **formato** do código de rastreio, nunca um valor fixo.
O código depende do `orderId`, que muda a cada execução.

## 8. `smoke-correlation-id.sh` (8 passos)

Prova, na stack real, a rastreabilidade da Fase 7 (plano 07-11): um pedido criado com um
`X-Correlation-Id` conhecido deixa o **mesmo** ID nos logs dos seis módulos. O caminho completo e
o funcionamento em cada serviço estão em
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md).

| Passo | O que faz | O que prova |
|---|---|---|
| 1–3 | Vendedor, empresa, comprador, produto com estoque 10 | Preparação |
| 4 | `POST /api/orders` com `X-Correlation-Id: smoke-cid-<hora>-<aleatório>` | A resposta do Gateway traz **um** header, com o mesmo valor |
| 5 | Espera `CONFIRMED` e o `ORDER_CONFIRMED` na linha do tempo | A saga e a notificação terminaram |
| 6 | `docker compose logs` de cada módulo, procurando `[<id>]` | O ID está nos logs do gateway, order, inventory, notification, catalog e auth |
| 7 | `GET` com `X-Correlation-Id: bad value!` | O Gateway troca o valor inválido por um UUID |
| 8 | Procura `bad value!` nos logs de todos | O valor inválido nunca aparece cru (proteção contra log injection) |

O passo 6 é o coração. A busca é por `[<id>]` com colchetes, porque é assim que o padrão de log
imprime o ID do MDC:

```bash
if docker compose logs --no-color "$svc" 2>&1 | grep -qF "[${cid}]"; then
```

O `-F` faz o `grep` tratar o texto como literal (sem expressão regular), e o laço em volta tenta
por até 15 segundos, porque a linha do notification-service só aparece depois que a mensagem
atravessa a fila.

## 9. As guardas: scripts que não testam o sistema, testam o projeto

Os scripts `check-*.sh` não ligam nada. Eles leem arquivos do repositório e conferem regras que
o compilador não confere. São o fiscal de obra que não usa a casa, mas confere se a planta foi
seguida. Os três rodam no job `guardas` do CI (ver [29-github-actions-ci.md](29-github-actions-ci.md)):

- **`check-no-skipped-tests.sh`** — procura `@Disabled`, `@EnabledIf...`, `@Ignore`,
  `Assumptions.assume...` e parentes em toda pasta `src/test/java`. Um teste desligado deixa o CI
  verde sem provar nada. Saída: `NENHUM TESTE PULADO` ou uma linha `TESTE PULADO: <arquivo>:<linha>`
  por ocorrência. Ver [15-testes.md](15-testes.md).
- **`check-adrs.sh`** — confere que cada ADR de `docs/adr/` tem as seções do formato, uma
  alternativa rejeitada e só cita decisões `D-xx` que existem. Saída: `ADR CHECK OK 11 ADRs`. Ver
  [32-adrs.md](32-adrs.md).
- **`check-coverage-matrix.sh`** — lê a matriz regra → teste e confere que todo teste citado
  existe e que nenhuma regra está como `LACUNA`. Saída: `COVERAGE CHECK OK <n> regras`. Ver
  [33-matriz-regra-teste.md](33-matriz-regra-teste.md).

E um quarto script, de apoio: **`ci-summary.sh <módulo>`** soma os relatórios `TEST-*.xml` do
Surefire e do Failsafe e escreve uma tabela em markdown no resumo do run do GitHub Actions. Ele
sempre sai com `0`: quem decide se o job falhou é o `./mvnw verify`, nunca o resumo.

Todas as guardas seguem a mesma ideia dos smokes: modo rigoroso (`set -euo pipefail`), uma linha
clara de sucesso no fim e uma linha por problema quando falha.

## Resumindo com uma analogia

Pensa na inspeção final de um carro saindo da fábrica. Cada peça (motor, freio, rádio) já foi
testada sozinha na bancada: são os testes Java. Mas antes de entregar, alguém **entra no carro
montado e dá uma volta no quarteirão**: liga, acelera, freia, liga o rádio, confere se a porta
tranca. É o smoke test. Ele não substitui os testes de bancada, mas pega o que só aparece com
tudo montado: um fio mal encaixado entre o motor e o painel, que nenhuma peça sozinha
revelaria.

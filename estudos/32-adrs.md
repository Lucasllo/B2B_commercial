# ADRs — o diário das decisões de arquitetura

Arquivos (plano 07-06):

- `docs/adr/README.md` — o índice
- `docs/adr/0001-saga-por-orquestracao-no-order-service.md` até
  `docs/adr/0011-codigo-duplicado-por-servico-em-vez-de-modulo-comum.md` — os 11 ADRs
- `scripts/check-adrs.sh` — a verificação automática

Continuação de [25-visao-geral-da-arquitetura.md](25-visao-geral-da-arquitetura.md), que mostra
**como** o sistema é. Os ADRs contam **por que** ele é assim.

## O que é um ADR, em uma frase

Um **ADR** (*Architecture Decision Record*, registro de decisão de arquitetura) é um arquivo curto
que guarda uma decisão: o problema, as opções que existiam, a escolhida, a descartada e o preço
da escolha.

Pense na ata de uma reunião de condomínio. Daqui a dois anos, um morador novo pergunta "por que o
portão é de grade e não de chapa?". Sem a ata, ninguém lembra, e alguém propõe trocar — repetindo
uma discussão que já foi feita. Com a ata, a resposta está escrita: "chapa foi descartada porque
bloqueava a ventilação da garagem". O ADR é essa ata, para o código.

Para um portfólio, ele tem um segundo papel: mostrar ao avaliador que as escolhas foram
**pensadas**, e não copiadas de um tutorial. "Por que você não usou Kafka?" tem resposta escrita.

## Por que os ADRs foram escritos só na Fase 7

As decisões foram tomadas ao longo das fases e já estavam registradas nos arquivos de
planejamento (`.planning/phases/*/NN-CONTEXT.md`), como `D-48`, `D-62` e assim por diante. Mas esses
arquivos são longos e misturam centenas de decisões pequenas. Os ADRs **destilam** as onze mais
importantes num lugar que qualquer pessoa acha: `docs/adr/`. Funcionam como um diário de
arquitetura navegável, não como proposta de decisões futuras — todos estão com status `Aceito`.

## O formato: MADR em português (D-108)

**MADR** (*Markdown Architectural Decision Records*) é um modelo de ADR muito usado: um arquivo
Markdown por decisão, sempre com as mesmas seções. Aqui ele foi traduzido. Cada arquivo se chama
`NNNN-titulo-em-kebab.md` (número com quatro dígitos e título com hífens) e tem:

1. **Front matter** — o bloco entre `---` no topo, com dados da decisão:

   ```yaml
   ---
   status: Aceito
   date: 2026-10-01
   decision-makers: Lucas Lopes
   ---
   ```

2. **`# NNNN — Título`** — o título já diz a escolha e o que ela substituiu, por exemplo
   "Transactional Outbox em vez de publicação direta no SQS".
3. **Contexto e problema** — a situação que obrigou a decidir.
4. **Fatores de decisão** — os critérios usados para comparar (custo, simplicidade, uma regra do
   projeto...).
5. **Alternativas consideradas** — a lista curta, cada uma marcada como **escolhida** ou
   **rejeitada**, com o motivo.
6. **Decisão** — o que foi feito, com detalhes concretos.
7. **Consequências** — "Bom: ..." e "Ruim: ...". O preço da escolha fica explícito.
8. **Prós e contras das alternativas** — cada opção com os seus pontos bons e ruins.
9. **Mais informações** — a linha `Fase de origem:`, os links para as decisões `D-xx` dos
   `CONTEXT.md` e para o código que implementa a decisão (D-110).

Exemplo real, de `0002-transactional-outbox-em-vez-de-publicacao-direta.md`:

```markdown
- **Transactional Outbox com relay por polling `@Scheduled`** — **escolhida**.
- **Publicação direta após o commit (dual-write)** — **rejeitada** para a saga: foi o que a Fase 3
  usou de propósito, com a limitação declarada (D-29, D-30), mas não oferece nenhuma garantia
  quando o processo cai entre o commit e o envio.
- **CDC com Debezium lendo o log do banco** — **rejeitada** neste escopo: é uma peça de
  infraestrutura a mais para operar e explicar; ...
```

## Por que a "alternativa rejeitada" é obrigatória

Uma decisão sem alternativa não é decisão, é só uma descrição. "Usamos PostgreSQL" não ensina nada.
"Usamos PostgreSQL **em vez de** MongoDB, porque precisávamos de transação entre duas tabelas" ensina
o critério — e o critério é o que permite, no futuro, saber se a decisão ainda vale. Se o motivo
deixou de existir, a decisão pode ser revista sem medo.

Também protege contra o "foi assim porque foi". Escrever por que a opção B perdeu obriga a ter
**considerado** a opção B. Por isso o projeto exige pelo menos uma alternativa rejeitada em cada
ADR, com o motivo concreto. E há uma regra a mais (registrada no plano 07-06): só entra como
alternativa o que de fato foi discutido nos arquivos de planejamento ou na pesquisa do projeto —
nada de inventar uma opção fraca só para ter o que rejeitar.

Algumas alternativas estão como **"rejeitada por enquanto"** (Resilience4j no 0010,
OpenTelemetry no 0008). É um jeito honesto de dizer: hoje não compensa, mas a porta fica aberta, e
o ADR já diz em que situação ela passaria a compensar.

## Os 11 ADRs, um por linha

| ADR | Decisão | Alternativa rejeitada (exemplo) |
|---|---|---|
| 0001 | A saga é **orquestrada pelo order-service**: a máquina de estados vive no próprio `Order`, que manda comandos e reage aos resultados | Coreografia pura (fluxo implícito, espalhado); motor de workflow como Camunda ou Temporal |
| 0002 | **Transactional Outbox**: o evento é gravado na tabela `outbox_event` na mesma transação, e um relay `@Scheduled` publica no SQS | Publicação direta após o commit (dual-write); CDC com Debezium |
| 0003 | **LocalStack** no lugar da AWS real, imagem fixada (`localstack/localstack:2026.08.3`), recursos criados por init hooks | AWS real com deploy contínuo (custo); mocks em memória no lugar de SQS e DynamoDB |
| 0004 | **Sem service discovery nem config server**: rotas estáticas no Gateway e serviços endereçados pelo nome do container | Eureka ou Consul; Spring Cloud Config Server |
| 0005 | **JWT emitido pelo próprio auth-service** (RSA) e validado localmente por cada serviço via JWKS | IdP externo; Spring Authorization Server; introspecção por chamada; jjwt |
| 0006 | **DynamoDB** para o histórico de notificações, partição `entityId` e ordenação `eventType#eventId` | PostgreSQL para tudo; tabela DynamoDB separada só para pedidos |
| 0007 | **Crédito serializado por empresa** com a linha de trava `company_credit_lock` e `SELECT ... FOR UPDATE` | Lock otimista com retry; advisory lock do Postgres; trava numa linha da tabela de pedidos |
| 0008 | **Correlation-ID próprio** (header + MDC + coluna do outbox + atributo SQS) | Micrometer Tracing / OpenTelemetry (por enquanto); `correlationId` dentro do corpo da mensagem |
| 0009 | **Gateway Server WebMVC** (servlet), no mesmo estilo dos outros serviços | Spring Cloud Gateway clássico, reativo (WebFlux) |
| 0010 | **Falha fechada**: catálogo ou auth sem resposta confiável vira 503 e nenhum pedido é criado, com timeouts explícitos (2 s de conexão, 3 s de leitura) | Resilience4j (por enquanto); falha aberta (aprovar sem consultar o limite) |
| 0011 | **Código duplicado por serviço** (outbox, `CorrelationContext`, `CorrelationIdFilter`) em vez de um módulo comum | Módulo Maven `common` compartilhado |

O próprio índice (`docs/adr/README.md`) sugere começar pelos quatro primeiros, os mais
estruturantes. Eles também são os **obrigatórios** do critério 5 da Fase 7: saga por orquestração,
Outbox, LocalStack e ausência de service discovery (D-109).

Notas de estudo relacionadas: [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md)
(0001), [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) (0002),
[06-jwks.md](06-jwks.md) (0005), [17-dynamodb.md](17-dynamodb.md) (0006),
[21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md) (0007) e
[27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md) (0008).

## `check-adrs.sh`: o fiscal do formato

Um ADR é texto, e texto se degrada: alguém renomeia um arquivo de código e o link do ADR quebra;
alguém cita `D-99` achando que era `D-96`. O script `scripts/check-adrs.sh` lê cada
`docs/adr/NNNN-*.md` e confere, só com `grep` e `sed`:

- o front matter tem `status: Aceito` e uma `date:` no formato `AAAA-MM-DD`;
- existem as seções Contexto e problema, Fatores de decisão, Alternativas consideradas, Decisão,
  Consequências, Prós e contras das alternativas e Mais informações;
- a palavra `rejeitada` aparece pelo menos uma vez;
- existe a linha `Fase de origem:`;
- todo link Markdown relativo aponta para um arquivo que existe;
- todo `D-<n>` citado existe como `**D-<n>:**` em algum `.planning/phases/*/*-CONTEXT.md`;
- o arquivo está listado no índice, e todo item do índice tem arquivo;
- nenhum valor parecido com um token do LocalStack foi colado no texto.

E, no fim, que os quatro ADRs obrigatórios existem, procurando o tema no título:

```bash
for theme in 'orquestração' 'Outbox' 'LocalStack' 'service discovery'; do
```

Cada problema vira uma linha `ADR CHECK FALHOU: <arquivo>: <problema>` e o script sai com erro. Sem
problemas, a última linha é `ADR CHECK OK 11 ADRs`. Ele roda no job `guardas` do CI (ver
[29-github-actions-ci.md](29-github-actions-ci.md)), então um ADR quebrado deixa o run vermelho.

Repare no limite: o script confere a **forma**, não a **verdade**. Ele garante que a seção
"Alternativas consideradas" existe e tem uma rejeitada, mas não sabe se o motivo faz sentido. A
qualidade do argumento continua sendo trabalho humano.

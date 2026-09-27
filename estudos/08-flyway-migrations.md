# Flyway e Migrations

Arquivos: `auth-service/src/main/resources/application.yml` e
`auth-service/src/main/resources/db/migration/`

## O que é Flyway, em uma frase

Flyway é uma ferramenta que controla **como o banco de dados evolui ao longo do tempo**,
de forma organizada e automática — em vez de alguém entrar manualmente no banco e
digitar `CREATE TABLE` toda vez que o projeto precisa de uma tabela nova.

## O problema que ele resolve

O código-fonte é versionado no Git — sempre se sabe exatamente qual era o estado do
código em qualquer commit do passado. Mas o **banco de dados** é diferente: é um
servidor rodando, com tabelas e dados dentro dele, que existe *fora* do Git.

Sem controle, isso vira caos rapidamente:
- O banco do seu computador tem uma tabela que o banco do colega não tem.
- Ninguém lembra exatamente quais comandos SQL já foram rodados em produção e quais não.
- Ao subir o projeto num servidor novo, alguém precisa "adivinhar" que comandos SQL
  rodar, na ordem certa.

Flyway resolve isso tratando as mudanças de banco de dados **como se fossem commits do
Git**: cada mudança é um arquivo numerado, aplicado uma única vez, sempre na mesma
ordem, com um registro de quais já foram aplicadas.

## O que é uma "migration" (migração)

É um arquivo `.sql` com uma mudança específica no banco — criar uma tabela, adicionar
uma coluna, inserir um dado inicial. No projeto, elas ficam em
`auth-service/src/main/resources/db/migration/`:

- **`V1__init_auth_schema.sql`** — cria as tabelas `companies` e `users`
- **`V2__seed_seller_admin.sql`** — insere o primeiro usuário administrador

O nome segue uma convenção rígida que o Flyway exige: `V<número>__<descrição>.sql`. O
`V1`, `V2` definem a **ordem** em que os arquivos devem ser aplicados — como capítulos
de um livro, não dá para ler o capítulo 2 antes do capítulo 1.

Os outros serviços que usam Postgres seguem exatamente o mesmo padrão — cada um com a
sua pasta `db/migration/`, no **seu próprio schema** e com a **sua própria**
`flyway_schema_history` (a "ficha de registro" explicada abaixo):

- **`catalog-service`** — `V1__init_catalog_schema.sql`, cria a tabela `products`
- **`inventory-service`** — `V1__init_inventory_schema.sql`, cria as tabelas
  `inventory` e `stock_reservations`
- **`order-service`** — `V1__init_order_schema.sql`, cria as tabelas `orders`,
  `order_items` e `company_credit_lock` (essa última é a trava de crédito por empresa,
  ver [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md))

O `notification-service` não tem migrations: ele não usa Postgres, e sim DynamoDB.

## Como o Flyway funciona, passo a passo

1. Quando o `auth-service` liga, o Flyway olha para dentro do banco e procura uma
   tabela especial que ele mesmo cria e mantém, chamada `flyway_schema_history`. Essa
   tabela é a "ficha de registro" — guarda quais migrations (V1, V2, ...) já foram
   aplicadas naquele banco específico.
2. Ele compara essa ficha com os arquivos `.sql` que existem na pasta `db/migration/`
   do projeto.
3. Qualquer migration que ainda **não** está na ficha, ele aplica agora, na ordem
   correta (V1, depois V2, depois V3...).
4. Depois de aplicar cada uma com sucesso, ele anota na ficha "V1 já foi aplicada, V2 já
   foi aplicada" — para nunca rodar a mesma migration duas vezes.

Isso significa que é seguro ligar o `auth-service` várias vezes: da primeira vez, ele
roda `V1` e `V2`. Da segunda vez em diante, ele vê que ambas já estão na ficha e não faz
nada — só continua ligando normalmente.

## Regra de ouro: migrations já aplicadas nunca mudam

O comentário no arquivo `V2__seed_seller_admin.sql` avisa isso explicitamente:

> "NUNCA edite este arquivo após aplicado — mudanças de seed vão para V3__...sql"

Por quê? O Flyway calcula um "checksum" (uma impressão digital) do conteúdo de cada
arquivo `.sql` na primeira vez que aplica. Se o arquivo `V2` for editado depois que já
rodou em algum ambiente (seu computador, ou pior, produção), o Flyway percebe que o
arquivo mudou e **recusa continuar** — ele para tudo com um erro, porque não sabe se
pode confiar que o histórico do banco ainda bate com os arquivos do projeto.

A solução correta para "consertar" algo que já foi aplicado nunca é editar o arquivo
antigo — é sempre criar um novo arquivo (`V3__...sql`) que corrige o problema, seguindo
em frente, nunca voltando atrás.

## Onde isso aparece configurado no projeto

No `application.yml`:

```yaml
flyway:
  schemas: auth
  default-schema: auth
  create-schemas: true
jpa:
  hibernate:
    ddl-auto: validate
```

Aqui tem uma decisão importante: `ddl-auto: validate`. O Hibernate (a ferramenta que
mapeia classes Java para tabelas do banco) tem um modo em que ele mesmo cria/altera
tabelas automaticamente (`ddl-auto: update`) — mas esse modo é perigoso e imprevisível
em produção (pode inferir errado e apagar uma coluna, por exemplo).

Neste projeto, o comentário no `application.yml` deixa claro: "o Flyway é dono do
schema; o Hibernate só valida". Ou seja, o Hibernate **nunca** cria ou modifica tabelas
sozinho — só confere se o que as classes Java esperam bate com o que realmente existe
no banco (criado pelo Flyway). Se não bater, a aplicação nem liga, avisando o
desenvolvedor do problema. Isso separa claramente as responsabilidades: Flyway comanda
a estrutura do banco; Hibernate só a usa.

**Curiosidade do `order-service`:** `order` é uma palavra reservada do SQL (a do
`ORDER BY`). Por isso, no `application.yml` dele, o `default_schema` do Hibernate é
escrito com aspas duplas dentro das aspas simples do YAML: `default_schema: '"order"'`.
Sem as aspas duplas, o PostgreSQL leria `order` como o comando e rejeitaria o SQL com
erro de sintaxe. Já o Flyway (`schemas: order`) e o `currentSchema=order` da URL do
banco tratam o nome como texto simples e não precisam desse truque.

## Um exemplo real de `V2`: a migration da saga

Na Fase 5, o `order-service` ganhou `V2__order_reservation_saga.sql` e o `inventory-service`
ganhou `V2__outbox_event.sql`. A `V1` de cada um **não foi tocada**: a mudança entrou como
arquivo novo, seguindo a regra de ouro acima. A `V2` do `order-service` mostra quatro coisas
que uma migration pode fazer:

1. **Trocar uma regra de validação.** Remove o `CHECK` antigo de `status` e cria outro com o
   valor novo `RESERVING`.
2. **Acrescentar colunas**, como `reservation_started_at`, `cancellation_code` e
   `confirmed_at`. Todas aceitam `NULL`, então as linhas antigas continuam válidas.
3. **Criar uma tabela nova**, a `outbox_event`, com um índice parcial
   (`WHERE published_at IS NULL`) que só indexa as linhas ainda pendentes.
4. **Migrar dados, não só estrutura.** Todo pedido que estava `APPROVED` ganha uma linha no
   outbox e passa para `RESERVING`, em SQL puro, antes de a aplicação subir. Assim nenhum
   pedido antigo fica fora da saga.

Um detalhe de técnica: o `CHECK` de `status` e o de `cancellation_code` já listam **todos** os
valores que a fase vai usar, até os que só entram no plano seguinte (`RESERVATION_TIMEOUT`). Assim
os próximos planos não precisam de outra migration só para acrescentar um valor.
Detalhes em [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md).

## Como isso funciona numa aplicação de produção

A ideia central não muda entre o computador local e produção — é exatamente a mesma
"ficha de registro" e os mesmos arquivos `.sql` — mas em produção, alguns cuidados
importam mais:

1. **Aplicado uma vez, para sempre**: uma vez que `V1` e `V2` rodaram no banco de
   produção, elas nunca mais rodam de novo, mesmo que o servidor reinicie mil vezes. Só
   migrations *novas* (V3, V4, ...) que ainda não estão na ficha são aplicadas.

2. **Deploy = código novo + migrations novas juntos**: quando uma nova versão da
   aplicação precisa de uma coluna nova no banco, cria-se um arquivo `V3__...sql` com o
   `ALTER TABLE` necessário. Ao subir a nova versão em produção, o Flyway detecta e
   aplica essa migration nova automaticamente, *antes* da aplicação começar a receber
   requisições — garantindo que o código novo nunca tente usar uma coluna que ainda não
   existe.

3. **Sem intervenção manual em produção**: ninguém precisa entrar manualmente no banco
   de produção e digitar SQL à mão (isso é arriscado e não deixa rastro). Tudo que muda
   no banco está documentado como arquivos versionados no Git, revisáveis em pull
   request como qualquer outro código.

4. **Histórico auditável**: a tabela `flyway_schema_history` funciona como um "log" —
   dá para consultar exatamente quando cada mudança estrutural foi aplicada em
   produção, e por quem (via o histórico do Git dos arquivos).

5. **Cuidado com migrations destrutivas**: em produção, uma migration que apaga uma
   coluna ou tabela com dados reais é irreversível — diferente do ambiente local, onde
   dá para recriar o banco do zero sem problema. Por isso, em times reais, migrations
   mais arriscadas costumam passar por revisão extra antes do deploy.

No caso deste projeto, como é local/portfólio (rodando via Docker Compose, conforme
[01-docker-compose.md](01-docker-compose.md)), o Flyway roda exatamente do mesmo jeito —
só que o "banco de produção" é o container Postgres local, recriável a qualquer momento
sem risco real de perda de dados.

# Design patterns usados no projeto

Um mapa dos padrões de projeto do OrderFlow: **onde** cada um aparece, **em que momento**
(fase) entrou e **por quê**. Cada linha aponta para a nota que detalha o assunto, quando
ela existe.

O projeto usa padrões em três níveis:

1. **arquitetura** — como os serviços se organizam;
2. **mensageria e dados** — como os serviços conversam e gravam com segurança;
3. **código** — os padrões clássicos (do livro "GoF" e similares) dentro das classes.

Um **design pattern** (padrão de projeto) é uma **solução conhecida para um problema que
aparece sempre**. É como uma receita de bolo testada: em vez de inventar do zero, você usa uma
forma que outras pessoas já provaram que funciona, e todo mundo que conhece o nome da receita
entende o que você fez.

## 1. Padrões de arquitetura

| Padrão | Onde | Momento | Por quê |
|---|---|---|---|
| **Microsserviços + banco por serviço** | 6 módulos; cada serviço com Postgres tem seu próprio schema (`currentSchema=order`, `inventory`...), e o notification usa DynamoDB | Fase 1 em diante | Cada serviço é dono dos seus dados. Ninguém lê a tabela do outro: pede pela API ou pela fila. Ver [25-visao-geral-da-arquitetura.md](25-visao-geral-da-arquitetura.md). |
| **API Gateway** | módulo `gateway` (Spring Cloud Gateway Server WebMVC) | Fase 1 | Uma porta de entrada única (8080). O cliente não precisa conhecer cinco endereços. Também é onde o Correlation-ID nasce e onde fica a Swagger UI única. Ver [10-gateway-application-yml.md](10-gateway-application-yml.md). |
| **Rotas estáticas (sem service discovery)** | `gateway/src/main/resources/application.yml` | Fase 1 (ADR 0004) | São poucos serviços e de endereço fixo. Eureka/Consul seria complexidade sem ganho. Ver [36-bom-trem-de-releases-e-outros-termos.md](36-bom-trem-de-releases-e-outros-termos.md). |
| **Validação local de token (JWKS)** | `auth-service` emite o JWT, os outros validam com a chave pública | Fases 1–2 (ADR 0005) | Os serviços não perguntam ao auth "esse token vale?" a cada requisição. Se o auth cair, quem já tem token continua funcionando. Ver [06-jwks.md](06-jwks.md). |

## 2. Padrões de mensageria e consistência entre serviços

O fluxo de pedido depende desses padrões.

| Padrão | Onde | Momento | Por quê |
|---|---|---|---|
| **Saga por orquestração** | `order-service` (`ReservationSagaStarter`, `OrderSagaService`) manda comandos ao inventory e decide o resultado | Fase 5 (ADR 0001) | Não existe transação de banco que abranja dois serviços. A saga divide o processo em passos locais, e o order-service é o "maestro" que sabe o estado. Orquestração foi preferida à coreografia porque deixa o fluxo legível num lugar só. Ver [26-saga-de-reserva-de-estoque.md](26-saga-de-reserva-de-estoque.md). |
| **Transação compensatória** | `ReleaseStock` quando a saga falha ou expira; `SagaTimeoutJob` | Fase 5 (05-04) | Na saga não há "rollback". Para desfazer, executa-se a ação contrária: devolver o estoque reservado. |
| **Transactional Outbox** | tabela `outbox_event` + `OutboxWriter` em order e inventory | Fase 5 (ADR 0002) | Evita o *dual-write*: o evento é gravado **na mesma transação** que o dado, então os dois são salvos juntos ou nenhum é salvo. Ver [16-sqs-outbox-mensageria.md](16-sqs-outbox-mensageria.md) e [34-lock-otimista-e-relay.md](34-lock-otimista-e-relay.md). |
| **Polling Publisher (relay)** | `OutboxRelay` + `OutboxRelayJob` (`@Scheduled`, a cada 1 s), com `FOR UPDATE SKIP LOCKED` | Fase 5 | É o "carteiro" que leva a outbox até o SQS. O `SKIP LOCKED` deixa várias instâncias trabalharem sem pegar a mesma linha. |
| **Comando/Evento (mensagens com intenção)** | comandos: `ReserveStock`, `ReleaseStock`, `ShipStock`; eventos: `StockReserved`, `StockReservationFailed`, `STOCK_ADJUSTED`, `ORDER_*` | Fases 3–6 | Um comando diz "faça isto" e tem um destinatário. Um evento diz "isto aconteceu" e quem quiser escuta. Separar os dois deixa claro quem manda em quem. |
| **Consumidor idempotente** | inventory (pela reserva já existente ou pelo estado do pedido); notification (chave determinística `eventType#eventId` + `putItem` sem condição, que só sobrescreve o mesmo item) | Fases 3 e 5 | O SQS entrega **pelo menos uma vez**, então a mesma mensagem pode chegar duas vezes. Processar de novo não pode duplicar nada. Ver [19-sqslistener-consumo.md](19-sqslistener-consumo.md). |
| **Dead Letter Queue** | filas `*-dlq` do LocalStack; `InvalidShipStockException` é relançada para ir à DLQ | Fase 5, reforçado no 07-09 (WR-01) | Uma mensagem que falha sempre não pode travar a fila nem sumir em silêncio. Ela vai para uma "caixa de problemas" para análise. |
| **Lápide (tombstone)** | `StockReservation.tombstone` no inventory | Fase 5 (05-04) | A fila SQS padrão não garante ordem. Se o `ReleaseStock` chegar **antes** do `ReserveStock`, a lápide marca "este pedido já foi cancelado" e a reserva atrasada é recusada. |
| **Correlation ID** | `CorrelationIdFilter` e `CorrelationContext` nos 6 módulos, coluna `correlation_id` na outbox, atributo SQS | Fase 7 (ADR 0008) | Seguir **uma** requisição pelos logs de todos os serviços com um `grep`, sem precisar de um sistema de tracing completo. Ver [27-correlation-id-e-mdc.md](27-correlation-id-e-mdc.md). |

## 3. Padrões de persistência e concorrência

| Padrão | Onde | Momento | Por quê |
|---|---|---|---|
| **Repository** | interfaces `*Repository` (Spring Data JPA); `NotificationRepository` para o DynamoDB | Fase 1 em diante | Esconde "como" o dado é buscado. O service pede `findById` sem escrever SQL. Ver [23-anotacoes-de-repository.md](23-anotacoes-de-repository.md). |
| **Lock otimista + retry** | `@Version` em `Inventory` + `@Retryable` no `InventoryService` | Fase 2 | Reservas simultâneas do mesmo produto não podem se sobrescrever. Conflitos são raros, então é mais barato conferir na saída e tentar de novo. Ver [34-lock-otimista-e-relay.md](34-lock-otimista-e-relay.md) e [14-spring-retry.md](14-spring-retry.md). |
| **Lock pessimista** | `CompanyCreditLocker` (`SELECT ... FOR UPDATE`) e trava da linha do pedido na saga | Fases 4–5 (ADR 0007) | Na checagem de crédito, dois pedidos da mesma empresa **não podem** passar juntos, ou os dois "caberiam" no limite. Aqui a briga é provável e o erro é caro, então se tranca a porta. Ver [21-credito-e-trava-por-empresa.md](21-credito-e-trava-por-empresa.md). |
| **Transação obrigatória (`Propagation.MANDATORY`)** | `OutboxWriter`, `CompanyCreditLocker`, `OrderTimelineEvents`, `ReservationSagaStarter` | Fases 4–6 | Garante **em código** que esses métodos só rodam dentro de uma transação aberta. Se alguém chamar fora, dá erro na hora, em vez de gravar o evento separado do dado. |
| **Versionamento de schema (migrations)** | Flyway `V1__...`, `V2__...` por serviço | Fase 1 em diante | O banco evolui em passos numerados e reproduzíveis. Ver [08-flyway-migrations.md](08-flyway-migrations.md). |

## 4. Padrões de código (GoF e similares)

| Padrão | Onde | Por quê |
|---|---|---|
| **Máquina de estados** | `OrderStatus.transitions()` / `canTransitionTo` + `Order.moveTo` (Fase 6) | Todas as 9 transições permitidas vivem **numa tabela só**, e `moveTo` é o único ponto que muda o status. Uma transição inválida vira 409, e um teste confere os 81 pares. Ver [30-ciclo-de-vida-expedicao-e-entrega.md](30-ciclo-de-vida-expedicao-e-entrega.md). |
| **Strategy / Port-Adapter** | interface `CarrierGateway` + `SimulatedCarrierGateway` (Fase 6) | O pedido depende de uma **interface**, não de uma transportadora real. Hoje a implementação é simulada. Amanhã, trocar por uma integração real com os Correios não mexe no pedido. |
| **Agregado (DDD)** | `Order` com métodos `confirm`, `ship`, `deliver` | As regras ficam **dentro** da entidade: ninguém faz `setStatus` de fora. |
| **Value Object (`record` imutável)** | `CarrierAssignment`, `PricedItem`, `ReservationOutcome`, DTOs | Dados que não mudam depois de criados. O `CarrierAssignment` recusa no construtor um código de rastreio inválido. Ver [12-records-e-anotacoes.md](12-records-e-anotacoes.md). |
| **DTO** | pastas `dto/` (`CreateOrderRequest`, `OrderResponse`...) | Separa o que trafega na API da entidade do banco. Não vaza coluna interna e evita *mass assignment*. |
| **Static Factory Method** | `OrderLifecycleEvent.created(...)`, `.approved(...)`; `StockReservedEvent.of(...)` | Um método com nome para cada tipo de evento. Isso é mais legível que um construtor com dez parâmetros, e cada fábrica garante que só os campos daquele tipo são preenchidos. Ver [31-linha-do-tempo-do-pedido.md](31-linha-do-tempo-do-pedido.md). |
| **Builder** | `RestClient.builder()`, `MessageBuilder.withPayload(...)`, `JwtClaimsSet.builder()` | Monta objetos com muitas opções passo a passo. São builders das bibliotecas usados pelo projeto. |
| **Decorator** | `HttpServletRequestWrapper` / `HttpServletResponseWrapper` no `CorrelationIdFilter` do Gateway | "Embrulha" a requisição e a resposta originais e muda só o necessário: um único header `X-Correlation-Id` na ida e o eco do serviço ignorado na volta. |
| **Chain of Responsibility** | filtros `OncePerRequestFilter` (`CorrelationIdFilter`) e a `SecurityFilterChain` | Cada filtro faz sua parte e passa adiante com `chain.doFilter(...)`. Ver [05-security-config.md](05-security-config.md). |
| **Interceptor** | `requestInterceptor` no `ClientConfig` do order-service | Toda chamada HTTP para o catálogo e o auth leva o `X-Correlation-Id` automaticamente, sem repetir código em cada chamada. |
| **Scope / RAII (try-with-resources)** | `CorrelationContext.Scope extends AutoCloseable` | Abre o MDC e **garante** a limpeza no fim, mesmo com exceção. Sem isso, a thread reaproveitada herdaria o ID da mensagem anterior. |
| **Guard** | `CompanyGuard` (auth-service), checagem de `companyId` nos outros serviços | Centraliza a regra "uma empresa não vê dados da outra". Ver [11-companyguard.md](11-companyguard.md). |
| **Tratamento centralizado de exceções** | `@RestControllerAdvice` `GlobalExceptionHandler` em cada serviço | Um lugar só converte exceções em respostas JSON padronizadas e sem detalhes internos. Ver [09-global-exception-handler.md](09-global-exception-handler.md). |
| **Fail fast** | `@ConfigurationProperties` + `@Validated` (`ClientProperties`) | Uma configuração errada derruba a aplicação **na subida**, e não no primeiro pedido. Ver [20-configuration-properties.md](20-configuration-properties.md). |

### Padrões que o próprio Spring aplica por você

- **Injeção de dependência / Inversão de controle:** os serviços recebem colaboradores pelo
  construtor; quem cria e liga os objetos é o Spring.
- **Singleton:** cada `@Service` existe uma vez só no contexto.
- **Proxy (AOP):** `@Transactional` e `@Retryable` funcionam porque o Spring envolve o bean
  num proxy. É por isso que o projeto separa classes, como o `OrderCreationService` e o
  `OrderService` (ver [22-services-de-pedido.md](22-services-de-pedido.md)), e se preocupa com
  a ordem entre retry e transação (ver [14-spring-retry.md](14-spring-retry.md)).
- **Template:** o `SqsTemplate` (usado pelo relay através da interface `SqsOperations`) cuida
  da parte repetitiva de falar com o SQS, e o projeto só fornece a mensagem e a fila.

## 5. Padrões rejeitados de propósito

Os ADRs também registram o que ficou de fora (ver [32-adrs.md](32-adrs.md)):

- **Circuit breaker (Resilience4j):** o sistema "falha fechado" (ADR 0010).
- **Módulo comum compartilhado:** o código é duplicado por serviço (ADR 0011).
- **Tracing distribuído (OpenTelemetry):** foi usado o Correlation-ID próprio (ADR 0008).
- **Motor de workflow (Camunda/Temporal):** a saga é escrita em código (ADR 0001).

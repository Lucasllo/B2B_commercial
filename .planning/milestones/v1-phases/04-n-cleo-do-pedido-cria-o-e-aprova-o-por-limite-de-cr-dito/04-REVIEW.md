---
phase: 04-n-cleo-do-pedido-cria-o-e-aprova-o-por-limite-de-cr-dito
reviewed: 2026-09-25T00:00:00Z
depth: standard
files_reviewed: 67
files_reviewed_list:
  - auth-service/Dockerfile
  - catalog-service/Dockerfile
  - docker-compose.yml
  - gateway/Dockerfile
  - gateway/src/main/resources/application.yml
  - inventory-service/Dockerfile
  - notification-service/Dockerfile
  - order-service/Dockerfile
  - order-service/pom.xml
  - order-service/src/main/java/com/orderflow/order/OrderServiceApplication.java
  - order-service/src/main/java/com/orderflow/order/client/AuthServiceClient.java
  - order-service/src/main/java/com/orderflow/order/client/AuthServiceUnavailableException.java
  - order-service/src/main/java/com/orderflow/order/client/CatalogServiceClient.java
  - order-service/src/main/java/com/orderflow/order/client/CatalogServiceUnavailableException.java
  - order-service/src/main/java/com/orderflow/order/client/dto/CatalogProductResponse.java
  - order-service/src/main/java/com/orderflow/order/client/dto/CreditLimitResponse.java
  - order-service/src/main/java/com/orderflow/order/config/ClientConfig.java
  - order-service/src/main/java/com/orderflow/order/config/ClientProperties.java
  - order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java
  - order-service/src/main/java/com/orderflow/order/config/OpenApiConfig.java
  - order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java
  - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLock.java
  - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLockRepository.java
  - order-service/src/main/java/com/orderflow/order/credit/CompanyCreditLocker.java
  - order-service/src/main/java/com/orderflow/order/credit/CreditPolicy.java
  - order-service/src/main/java/com/orderflow/order/order/Order.java
  - order-service/src/main/java/com/orderflow/order/order/OrderController.java
  - order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderDecisionController.java
  - order-service/src/main/java/com/orderflow/order/order/OrderDecisionService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderItem.java
  - order-service/src/main/java/com/orderflow/order/order/OrderRepository.java
  - order-service/src/main/java/com/orderflow/order/order/OrderService.java
  - order-service/src/main/java/com/orderflow/order/order/OrderStatus.java
  - order-service/src/main/java/com/orderflow/order/order/PricedItem.java
  - order-service/src/main/java/com/orderflow/order/order/dto/ApproveOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/CreateOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemRequest.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderItemResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/OrderSummaryResponse.java
  - order-service/src/main/java/com/orderflow/order/order/dto/RejectOrderRequest.java
  - order-service/src/main/java/com/orderflow/order/order/exception/DuplicateOrderItemsException.java
  - order-service/src/main/java/com/orderflow/order/order/exception/InvalidOrderItemsException.java
  - order-service/src/main/java/com/orderflow/order/order/exception/OrderNotFoundException.java
  - order-service/src/main/java/com/orderflow/order/order/exception/OrderNotPendingException.java
  - order-service/src/main/java/com/orderflow/order/order/exception/OrderTotalOutOfRangeException.java
  - order-service/src/main/resources/application.yml
  - order-service/src/main/resources/db/migration/V1__init_order_schema.sql
  - order-service/src/test/java/com/orderflow/order/AbstractIntegrationTest.java
  - order-service/src/test/java/com/orderflow/order/CreditLimitBoundaryConcurrencyIT.java
  - order-service/src/test/java/com/orderflow/order/CreditLockAndExposureIT.java
  - order-service/src/test/java/com/orderflow/order/OpenApiDocsIT.java
  - order-service/src/test/java/com/orderflow/order/OrderApprovalIT.java
  - order-service/src/test/java/com/orderflow/order/OrderControllerIT.java
  - order-service/src/test/java/com/orderflow/order/OrderCreationEdgeCasesIT.java
  - order-service/src/test/java/com/orderflow/order/OrderDecisionConcurrencyIT.java
  - order-service/src/test/java/com/orderflow/order/OrderListIT.java
  - order-service/src/test/java/com/orderflow/order/client/DownstreamClientsTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderCreationServiceTest.java
  - order-service/src/test/java/com/orderflow/order/order/OrderDomainTest.java
  - order-service/src/test/java/com/orderflow/order/support/ConcurrentRequests.java
  - order-service/src/test/java/com/orderflow/order/support/DownstreamStubServer.java
  - order-service/src/test/java/com/orderflow/order/support/OrderTestInfrastructure.java
  - order-service/src/test/java/com/orderflow/order/support/TestJwt.java
  - order-service/src/test/resources/application-test.yml
  - pom.xml
  - scripts/smoke-order-flow.sh
findings:
  critical: 0
  warning: 2
  info: 4
  total: 6
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-09-25
**Depth:** standard
**Files Reviewed:** 67
**Status:** issues_found

## Summary

Revisão do núcleo do pedido (order-service): criação com validação de catálogo, decisão automática/manual por limite de crédito sob trava por empresa, e a infraestrutura de compose/Dockerfiles que sobe o Gateway na frente do serviço. A lógica de negócio central — `CreditPolicy`, `Order` (máquina de estados), `CompanyCreditLocker`/`CompanyCreditLockRepository` (trava serializada por empresa), `OrderCreationService`/`OrderService` (separação não-transacional/transacional) — foi lida linha a linha e está correta nos casos testados: igualdade aprova (D-36), soma de exposição restrita a `CREDIT_CONSUMING`, tudo-ou-nada na criação, snapshot congelado, isolamento por empresa no `GET /{id}` e na listagem, e falha fechada em qualquer resposta não confiável dos vizinhos (auth-service/catalog-service). Os testes de concorrência por socket real (`CreditLimitBoundaryConcurrencyIT`, `OrderDecisionConcurrencyIT`) validam a serialização da trava sob disputa genuína, não apenas em `MockMvc`.

Não foi encontrado nenhum problema de severidade Critical (nenhuma vulnerabilidade de segurança, bypass de autorização ou corrupção de dado comprovada). Os itens abaixo são de manutenibilidade/robustez (Warning) e qualidade (Info) — nenhum bloqueia o merge, mas merecem registro.

O achado mais relevante confirma a suspeita levantada pelo log de `./mvnw verify` (aviso do Spring Data sobre serializar `PageImpl` diretamente): `GET /orders` devolve `Page<OrderSummaryResponse>` cru, cujo formato JSON não tem garantia de estabilidade entre versões do Spring Data — ver WR-01.

## Warnings

### WR-01: `GET /orders` serializa `Page<T>` cru — formato JSON sem garantia de estabilidade

**File:** `order-service/src/main/java/com/orderflow/order/order/OrderController.java:72` (assinatura do endpoint) e `order-service/src/main/java/com/orderflow/order/order/OrderService.java:95` (retorno `Page<OrderSummaryResponse>`)

**Issue:** `OrderController.list` devolve `Page<OrderSummaryResponse>` diretamente ao Jackson (via `PageImpl`, produzido por `Page.map` em `OrderService.list`). Isso é exatamente o padrão contra o qual o Spring Data avisa em tempo de execução — confirmado no log de `./mvnw verify` durante `OrderListIT`: *"Serializing PageImpl instances as-is is not supported... no guarantee about the stability of the resulting JSON structure"*. `docs/API.md` documenta um formato fixo e plano (`content`, `totalElements`, `totalPages`, `size`, `number` como irmãos), que é o comportamento *atual* — mas não é um contrato que o Spring Data garante entre versões (por exemplo, versões que adotam `PagedModel`/`PageSerializationMode.VIA_DTO` por padrão passam a aninhar os metadados sob uma chave `page: {...}`, quebrando silenciosamente qualquer consumidor do formato documentado hoje, sem qualquer sinal em tempo de compilação). Isso é risco de estabilidade de contrato de API, não um bug funcional imediato — mas é exatamente o tipo de regressão silenciosa que o próprio framework está avisando que pode acontecer.

**Fix:** Substituir o retorno cru por um DTO de página estável e de propriedade do próprio serviço, por exemplo:

```java
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int size, int number) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getTotalElements(),
                page.getTotalPages(), page.getSize(), page.getNumber());
    }
}
```

e mudar as assinaturas de `OrderService.list`/`OrderController.list` para `PageResponse<OrderSummaryResponse>`. Isso fixa exatamente o contrato hoje documentado em `docs/API.md`, independente de qualquer mudança futura no módulo Jackson do `spring-data-commons`.

---

### WR-02: `AuthenticationEntryPoint` grava JSON sem charset explícito — risco latente de corrupção de acentuação

**File:** `order-service/src/main/java/com/orderflow/order/config/SecurityConfig.java:58-67`

**Issue:** O `AuthenticationEntryPoint` customizado escreve o corpo do 401 diretamente em `response.getWriter()` via `objectMapper.writeValue(...)`, depois de `response.setContentType(MediaType.APPLICATION_JSON_VALUE)` — que é só `"application/json"`, sem parâmetro de charset. Sem uma chamada explícita a `response.setCharacterEncoding("UTF-8")`, a Servlet API usa o encoding padrão do contêiner para `getWriter()` (ISO-8859-1, conforme a especificação Jakarta Servlet, quando nenhum encoding foi definido). Hoje isso não se manifesta porque as duas strings gravadas por este handler ("unauthorized", "Authentication is required") são puro ASCII — mas todo o resto do serviço grava mensagens com acentuação (`"cliente estratégico"`, `"sem histórico de pagamento"`, etc., testado em `OrderApprovalIT`) e é servido corretamente porque passa pelo conversor Jackson padrão do Spring MVC (que define UTF-8 automaticamente). Este ponto de entrada é o único lugar do serviço que grava JSON por fora desse conversor — uma futura mensagem de erro com acentuação (i18n, ou uma mudança de texto) sairia corrompida silenciosamente, sem nenhum teste hoje cobrindo esse caractere nesta rota especificamente.

**Fix:**
```java
return (request, response, authException) -> {
    response.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    ...
};
```

## Info

### IN-01: `GlobalExceptionHandler.handleAuthentication` é, na prática, código morto

**File:** `order-service/src/main/java/com/orderflow/order/config/GlobalExceptionHandler.java:122-126`

**Issue:** O `@ExceptionHandler(AuthenticationException.class)` neste `@RestControllerAdvice` nunca é alcançado em condições normais: qualquer `AuthenticationException` lançada pela cadeia de filtros do resource server (JWT ausente, assinatura inválida, `exp` no passado, `iss` divergente) é interceptada pelo `AuthenticationEntryPoint` customizado registrado em `SecurityConfig` — que roda ANTES do `DispatcherServlet`, ou seja, antes de qualquer `@RestControllerAdvice` ter chance de agir (o próprio Javadoc de `SecurityConfig` documenta essa ordem). Não há, no código atual, nenhum ponto de negócio dentro de um método `@RequestMapping` que lance `AuthenticationException` diretamente — todas as rotas exigem `.anyRequest().authenticated()`, então ao chegar num controller a `Authentication` já existe. Não é um bug (o corpo de erro produzido pelos dois caminhos é idêntico), mas é lógica morta que confunde quem lê o handler pensando que ele está em uso.

**Fix:** Remover o handler (deixando só o `AuthenticationEntryPoint` como fonte única de verdade do 401), ou adicionar um comentário explícito explicando que ele existe apenas como defesa em profundidade para uma futura `AuthenticationException` lançada fora da cadeia de filtros (nenhuma existe hoje).

### IN-02: Soma do total duplicada entre `OrderCreationService` e `Order.create`

**File:** `order-service/src/main/java/com/orderflow/order/order/OrderCreationService.java:97-101` e `order-service/src/main/java/com/orderflow/order/order/Order.java:87-93`

**Issue:** `OrderCreationService.create` soma os subtotais de `pricedItems` num loop só para validar o limite de dígitos (`guardTotalFitsInColumn`), e então `Order.create` (chamado depois, dentro da transação) refaz exatamente a mesma soma para gravar `order.total`. As duas somas são garantidamente iguais (mesma lista, mesmo `pricedItem.subtotal()`), então não há bug — mas é uma soma repetida em dois lugares que só coincidem por convenção, não por compartilhamento de código; se alguém mudar como `Order.create` soma o total (ex.: arredondamento diferente) sem atualizar `guardTotalFitsInColumn`, a guarda de faixa pode divergir silenciosamente do total realmente persistido.

**Fix:** Calcular o total uma única vez em `OrderCreationService.create`, validar a faixa, e passar o `BigDecimal total` já pronto para `Order.create` (ou para um `Order.create` sobrecarregado), em vez de recalculá-lo dentro do agregado.

### IN-03: Cinco Dockerfiles quase idênticos (única diferença é o nome do módulo)

**Files:** `auth-service/Dockerfile`, `catalog-service/Dockerfile`, `gateway/Dockerfile`, `inventory-service/Dockerfile`, `notification-service/Dockerfile`, `order-service/Dockerfile`

**Issue:** Os seis Dockerfiles do reactor são idênticos byte a byte, exceto pelo nome do módulo Maven (`-pl auth-service` vs `-pl order-service` etc.) e pelo nome do jar copiado no estágio final. Qualquer mudança de política (ex.: trocar a tag do `eclipse-temurin`, adicionar outra dependência de sistema além do `curl`) precisa ser replicada manualmente em 6 arquivos — risco real de um deles ficar desatualizado (já existem pequenas divergências de acentuação nos comentários entre os arquivos mais antigos e os mais novos, sinal de que a cópia já diverge sutilmente).

**Fix:** Parametrizar um único `Dockerfile` na raiz com `ARG SERVICE_NAME`, referenciado em `docker-compose.yml` via `build.args`, ou gerar os Dockerfiles a partir de um template no CI. Não bloqueia esta fase, mas reduz a superfície de manutenção à medida que mais serviços forem adicionados.

### IN-04: `AuthServiceClient.getCreditLimit` não valida o sinal do limite de crédito recebido

**File:** `order-service/src/main/java/com/orderflow/order/client/AuthServiceClient.java:50-54`

**Issue:** A checagem de resposta confiável cobre corpo nulo, `creditLimit` nulo e `companyId` divergente (D-39) — mas não rejeita um `creditLimit` negativo. Um auth-service com um bug que devolvesse um limite negativo faria `CreditPolicy.fitsWithinLimit` recusar automaticamente qualquer pedido novo daquela empresa (todos caem em `PENDING_APPROVAL`), o que é um efeito degradado, não uma falha de segurança — mas está fora do padrão "fail-closed" que o resto do método já aplica a outras formas de resposta não confiável.

**Fix:** Somar `response.creditLimit().signum() < 0` à condição que já existe na linha 50, tratando limite negativo como resposta não confiável (mesmo padrão do `CatalogServiceClient`, que já rejeita `price().signum() < 0`).

---

_Reviewed: 2026-09-25_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_

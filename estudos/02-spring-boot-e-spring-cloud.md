# Spring Boot e Spring Cloud

> Termos como **BOM**, **bleeding-edge**, **trem de releases**, **churn** e **service
> discovery** estão explicados para iniciantes em
> [36-bom-trem-de-releases-e-outros-termos.md](36-bom-trem-de-releases-e-outros-termos.md).

## Spring Boot

**Versão: 3.5.16**, fixada em `pom.xml` via BOM (`spring-boot-dependencies`) importado
no `dependencyManagement` — não via `spring-boot-starter-parent`. O reactor hospeda hoje
6 módulos (`gateway`, `auth-service`, `catalog-service`, `inventory-service`,
`notification-service` e `order-service`, ver
[01-docker-compose.md](01-docker-compose.md)); importar o BOM manualmente dá controle
total sobre a árvore de dependências, em vez de herdar tudo do parent padrão do Spring.

**Por quê 3.5.x e não 4.0**: é a última minor da linha 3.x, mais madura e testada. A 4.0
força migração para Jackson 3 + Jakarta EE 11, o que geraria atrito de migração sem
agregar valor ao que o projeto quer demonstrar (lógica de saga, limite de crédito,
orquestração de eventos). Um portfólio para vaga Pleno se beneficia mais de uso
idiomático do que é usado hoje em produção do que de bleeding-edge.

Consequência prática dessa escolha: como não herda de `spring-boot-starter-parent`, o
`<parameters>true</parameters>` do compilador precisou ser configurado manualmente — sem
isso, `@PathVariable` e SpEL em `@PreAuthorize` quebrariam (bug real encontrado em
`CompanyControllerIT`).

## Spring Cloud

**Versão: 2025.0.3 ("Northfields")** — o trem de releases compatível com Spring Boot
3.5.x. Misturar trens incompatíveis (ex: 2023.0.x com Boot 3.5) é um erro comum e difícil
de depurar em projetos Spring Cloud, por isso a versão foi travada e documentada como
decisão de checkpoint humano.

**Uso concreto**: do trem de releases do Spring Cloud (`org.springframework.cloud`),
hoje só o módulo `gateway` consome algo, via
`spring-cloud-starter-gateway-server-webmvc`. Essa é a variante **não-reativa**
(servlet-based) do Spring Cloud Gateway. O motivo: a gateway clássica
(WebFlux/Project Reactor) obrigaria a equipe a aprender um segundo modelo de
programação (reativo) só para rotear requisições, sem ganho de demonstração para a
vaga-alvo — que valoriza domínio de Spring MVC/JPA tradicional, não reactive streams.

**Cuidado com o nome parecido: Spring Cloud AWS.** O `inventory-service` e o
`order-service` (`spring-cloud-aws-starter-sqs`, para a saga e o outbox) e o
`notification-service` (starters de SQS e de DynamoDB) usam o **Spring Cloud AWS**
(`io.awspring.cloud`). Apesar do nome, ele é um
projeto separado, que **não faz parte** do trem 2025.0.3 — tem versão própria
(**3.4.2**), gerenciada pelo seu próprio BOM (`spring-cloud-aws-dependencies`), também
importado no `dependencyManagement` do `pom.xml` raiz. A linha 3.4.x foi escolhida
porque a 4.x já exige Spring Boot 4.

O `auth-service`, por outro lado, **não depende de Spring Cloud** — ele só usa Spring
Boot puro (Web, Security, OAuth2 Resource Server, JPA, Validation, Actuator) porque seu
papel é emitir/validar JWT e persistir dados, sem necessidade de descoberta de serviço ou
roteamento.

## Por que essa combinação no geral

- **Java 21 + Boot 3.5 + Cloud 2025.0.3** é o par de versões "atual mas estável" do
  mercado Java/Spring hoje — evita tanto tecnologia obsoleta quanto churn de versão
  recém-lançada, mantendo o foco no que a vaga quer ver: arquitetura de microsserviços
  orientada a eventos, não malabarismo de upgrade.
- **Gateway estático sem service discovery** (Eureka/Consul): com um número pequeno e
  conhecido de serviços via docker-compose (6 módulos hoje, com o gateway roteando para
os outros 5), service
  discovery seria complexidade desnecessária — rotas estáticas em `application.yml`
  bastam.

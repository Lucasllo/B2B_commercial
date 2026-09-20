# Estudos — OrderFlow

Notas de estudo pessoal para entender, elemento por elemento, como o projeto OrderFlow
funciona. Cada arquivo explica uma parte do projeto em nível iniciante, com analogias e
sem pressupor conhecimento prévio.

Diferente de [`docs/`](../docs/), que é documentação voltada a terceiros/portfólio, esta
pasta é para consulta própria durante o aprendizado.

## Índice

1. [Docker Compose](01-docker-compose.md) — os serviços que sobem juntos localmente
   (Postgres, LocalStack, auth-service, gateway) e como eles dependem uns dos outros
2. [Spring Boot e Spring Cloud](02-spring-boot-e-spring-cloud.md) — quais versões o
   projeto usa e por que essas escolhas foram feitas
3. [Dockerfile](03-dockerfile.md) — como cada microsserviço é empacotado em uma imagem
   Docker, do código-fonte até o container rodando
4. [Maven Wrapper (mvnw / mvnw.cmd)](04-maven-wrapper.md) — como o projeto compila sem
   exigir que o Maven esteja instalado na máquina
5. [SecurityConfig](05-security-config.md) — como o `auth-service` decide quem pode
   acessar o quê
6. [JWKS](06-jwks.md) — como outros serviços vão confirmar que um token de login é
   autêntico, sem perguntar ao `auth-service` toda vez
7. [Pasta target](07-pasta-target.md) — a pasta de saída gerada pelo Maven a cada build,
   por que ela não é versionada e por que pode ser apagada sem medo
8. [Flyway e Migrations](08-flyway-migrations.md) — como o banco de dados evolui de
   forma controlada e versionada, e como isso funciona em produção
9. [GlobalExceptionHandler](09-global-exception-handler.md) — como o `auth-service`
   padroniza e protege as respostas de erro em toda a aplicação
10. [application.yml do gateway](10-gateway-application-yml.md) — como o roteamento de
    requisições para os outros serviços é configurado
11. [CompanyGuard](11-companyguard.md) — como o projeto impede que uma empresa veja ou
    altere dados de outra empresa
12. [Records e suas anotações](12-records-e-anotacoes.md) — o que é um `record` em Java
    e todas as anotações de validação/serialização usadas nos DTOs do projeto
13. [springdoc-openapi (Swagger UI)](13-springdoc-openapi.md) — como cada serviço passou a
    expor documentação interativa navegável, com botão Authorize funcional para testar
    endpoints protegidos direto no navegador

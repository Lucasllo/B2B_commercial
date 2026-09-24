# OIDC e a claim `iss` — validando quem emitiu o token

Arquivos: `auth-service/src/main/java/com/orderflow/auth/auth/TokenService.java`,
`inventory-service/src/main/resources/application.yml`,
`catalog-service/src/main/resources/application.yml`,
`notification-service/src/main/resources/application.yml`.

Continuação de [06-jwks.md](06-jwks.md) — lá ficou registrada uma lacuna real
encontrada na revisão da Fase 2: os serviços validavam a assinatura do JWT
(via `jwk-set-uri`), mas não validavam quem o emitiu. Esta nota explica a
correção feita depois (T-03-02/WR-07).

## O que é uma claim, rapidinho

Um **JWT** (JSON Web Token) é o "crachá digital" que o `auth-service` emite
quando alguém faz login. Dentro desse crachá tem várias informações —
chamadas **claims** — como quem é o usuário (`sub`), quando expira (`exp`),
quando foi emitido (`iat`) etc. Cada claim é só um par chave-valor dentro do
token.

A **claim `iss`** ("issuer" = emissor) diz **quem emitiu o token**. No
projeto, isso é definido em `TokenService.java`:

```java
private static final String ISSUER = "orderflow-auth-service";
...
JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
        .issuer(ISSUER)
        ...
```

Todo token que o `auth-service` emite carrega, dentro de si, a informação
"eu fui emitido por `orderflow-auth-service`".

## O que é OIDC

**OIDC (OpenID Connect)** é um protocolo padrão construído em cima do
OAuth2, usado para autenticação — ele define, entre outras coisas, uma
forma padronizada de um serviço "descobrir" automaticamente como validar
tokens de um determinado emissor. Na prática, isso costuma envolver:

1. Você aponta para uma URL de "emissor" (`issuer-uri`), que é uma URL real
   (ex.: `https://accounts.google.com`).
2. O serviço que vai validar o token busca automaticamente, nessa URL, um
   documento padronizado de metadados (`/.well-known/openid-configuration`)
   que diz onde encontrar as chaves públicas de assinatura, entre outras
   coisas.

Isso é chamado de **"descoberta OIDC"**: você só configura a URL do emissor,
e o resto é descoberto automaticamente.

## Como o projeto usa (e não usa) OIDC

O projeto **não faz** essa descoberta automática. O comentário no
`application.yml` de cada resource server (inventory, catalog, notification)
explica exatamente por quê:

```yaml
security:
  oauth2:
    resourceserver:
      jwt:
        jwk-set-uri: ${...:http://localhost:8081/.well-known/jwks.json}
        # Com jwk-set-uri tambem definido, o Boot NAO faz descoberta OIDC: busca as chaves no
        # jwk-set-uri e usa o issuer-uri so como o valor esperado do claim iss. O valor nao e
        # uma URL porque o auth-service emite o iss literal orderflow-auth-service, que nao
        # depende de host nem de rede
        issuer-uri: ${...:orderflow-auth-service}
```

Duas propriedades diferentes, dois papéis diferentes:

- **`jwk-set-uri`** — diz explicitamente **onde buscar as chaves públicas**
  para verificar a assinatura do token (o endpoint JWKS do auth-service —
  ver [06-jwks.md](06-jwks.md)).
- **`issuer-uri`** — normalmente, no modo OIDC "completo", seria uma URL
  usada para descoberta automática. Mas quando `jwk-set-uri` **já** está
  definido explicitamente, o Spring Boot **não** faz mais essa descoberta —
  ele usa o `issuer-uri` só para uma coisa mais simples: **o valor que
  espera encontrar dentro da claim `iss` do token**.

Por isso o valor não é uma URL (`http://...`) — é o texto literal
`orderflow-auth-service`, o mesmo valor que o `TokenService` grava no token.
Não depende de rede nem de qual host está rodando o serviço.

## Por que essa validação existe — o bug real que ela fecha

Isso não é um detalhe cosmético. O comentário do arquivo é explícito:

> *"Fecha T-03-02/WR-07: sem esta linha, um token assinado pela chave
> confiável mas com iss diferente (ou ausente) era aceito, pois só
> assinatura e exp/nbf eram validados."*

Ou seja: **antes** dessa configuração existir (a lacuna registrada em
[06-jwks.md](06-jwks.md)), os serviços validavam apenas:

- a **assinatura** do token (prova que foi assinado com a chave privada do
  auth-service);
- as datas (`exp`/`nbf` — expiração e "não válido antes de").

...mas **não** verificavam de onde o token dizia ter vindo. Isso é um
problema real de segurança: um token tecnicamente assinado corretamente, mas
com um `iss` diferente do esperado (por exemplo, forjado para dizer que veio
de outro sistema, ou simplesmente com o campo ausente), seria **aceito do
mesmo jeito**. Com o `issuer-uri` configurado, o Spring Security passou a
**rejeitar** qualquer token cujo `iss` não seja exatamente
`orderflow-auth-service`.

## Resumindo com uma analogia

Pensa num crachá de acesso a um prédio:

- **A assinatura** é o holograma de segurança do crachá — prova que ele não
  foi falsificado por qualquer um.
- **`exp`/`nbf`** são as datas de validade impressas no crachá.
- **`iss`** é o campo "emitido por: [empresa X]" impresso no crachá.

Antes da correção, o segurança da portaria só checava o holograma e a
validade — aceitaria um crachá com holograma genuíno mas que dissesse
"emitido pela empresa errada". A correção faz o segurança também conferir
esse campo: "esse crachá foi emitido pela empresa certa
(`orderflow-auth-service`)?" Se não bater, a entrada é negada, mesmo que o
holograma seja autêntico.

A **descoberta OIDC** seria o equivalente a, em vez de o segurança já saber
de cor as regras da empresa X, ele ligar toda vez para uma central (a URL de
metadados) perguntando "quais são as regras de hoje?". O projeto pula essa
ligação — já configura as regras (`jwk-set-uri` + `issuer-uri`) direto,
porque o `auth-service` é um sistema conhecido e fixo, não um provedor de
identidade externo e dinâmico.

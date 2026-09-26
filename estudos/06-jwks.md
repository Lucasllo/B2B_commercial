# JWKS

Arquivos: `JwtIssuerConfig.java` e `JwksController.java`, em
`auth-service/src/main/java/com/orderflow/auth/config/`

## O que é JWKS, em uma frase

**JWKS** (JSON Web Key Set) é um "cartão de visitas público" que o `auth-service` expõe
na internet, contendo a **chave pública** que qualquer outro serviço pode usar para
conferir se um crachá JWT é realmente autêntico — sem nunca precisar perguntar ao
`auth-service` "esse token é seu mesmo?".

## Analogia: selo de lacre com duas metades

Imagine um selo de cera para lacrar cartas, mas mágico, com duas metades:

- A **chave privada** — fica em segredo, só o `auth-service` tem. Usada para
  **carimbar/assinar** um documento (o JWT).
- A **chave pública** — distribuída livremente. Não consegue criar um carimbo válido, só
  consegue **conferir** se um carimbo é autêntico.

Isso é **criptografia assimétrica** (as duas chaves são diferentes, ao contrário de uma
senha comum, onde a mesma chave tranca e destranca).

No código (`JwtIssuerConfig.java`), o par é gerado assim:

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
generator.initialize(2048);
KeyPair keyPair = generator.generateKeyPair();
```

**RSA** é o algoritmo matemático usado para gerar o par de chaves. `2048` é o tamanho da
chave em bits — quanto maior, mais difícil de quebrar por força bruta. Isso acontece uma
única vez, quando o `auth-service` liga.

## Onde a chave privada é usada

```java
@Bean
public JwtEncoder jwtEncoder() {
    ...
    return new NimbusJwtEncoder(jwkSource);
}
```

O `JwtEncoder` **carimba** (assina) um novo crachá JWT toda vez que alguém faz login com
sucesso, usando a chave **privada**, que nunca sai do `auth-service`. Vazar a chave
privada permitiria que qualquer um forjasse crachás falsos se passando pelo
`auth-service` — por isso o código nunca loga o par de chaves nem a chave completa.

## Onde a chave pública é usada — o endpoint JWKS

```java
@GetMapping("/.well-known/jwks.json")
public Map<String, Object> jwks() {
    return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
}
```

Essa classe (`JwksController`) expõe um endereço web público —
`/.well-known/jwks.json` — onde qualquer serviço pode "buscar o cartão de visitas" do
`auth-service`, baixando a chave **pública**.

`toPublicJWK()` extrai só a metade pública da chave, descartando deliberadamente a parte
privada antes de publicar (os componentes internos do RSA — `d, p, q, dp, dq, qi` —
jamais podem vazar).

`.well-known/` é uma convenção padrão da internet: uma pasta "conhecida por convenção"
onde sistemas esperam encontrar metadados públicos de um serviço.

## Conexão com o SecurityConfig

Essa rota está liberada para todo mundo (ver [05-security-config.md](05-security-config.md)):

```java
.requestMatchers("/auth/login", "/.well-known/jwks.json", "/actuator/health/**",
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
```

(As rotas `swagger-ui`/`v3/api-docs` são da documentação da API — ver
[05-security-config.md](05-security-config.md). A que importa aqui é
`/.well-known/jwks.json`.)

Faz sentido: se essa rota exigisse autenticação, seria um paradoxo — ninguém conseguiria
buscar a chave pública (necessária para *validar* um token) sem já ter um token válido.

## Como a validação acontece hoje, dentro do próprio `auth-service`

```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())...
```

O próprio `auth-service` **não precisa buscar sua própria chave pela internet** — ele já
tem o objeto `rsaKey` inteiro (público + privado) em memória, então o `JwtDecoder` usa a
chave pública diretamente do objeto Java, sem chamada HTTP: "validação local, sem
chamada em tempo de execução a nenhum serviço".

Além da assinatura e da expiração, esse decoder também exige que o claim `iss` do token
seja `orderflow-auth-service` (a constante `ISSUER` do `JwtIssuerConfig`):

```java
decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
```

## Para que serve o endpoint JWKS hoje

Na Fase 1, só existiam `auth-service` e `gateway` no projeto, e esta seção previa que os
próximos microsserviços precisariam validar os JWTs emitidos pelo `auth-service` sem ter
a chave em memória. Isso já aconteceu: os 4 resource servers — `catalog-service` e
`inventory-service` (Fase 2), `notification-service` (Fase 3) e `order-service`
(Fase 4) — configuram exatamente esse `jwk-set-uri` no seu `application.yml`, e no
docker-compose ele aponta para `http://auth-service:8081/.well-known/jwks.json` (via a
variável de ambiente `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`). O Spring
Security baixa a chave pública de lá automaticamente para conferir a assinatura dos
tokens recebidos — sem nunca precisar perguntar ativamente "esse token é válido?" ao
`auth-service` a cada requisição.

Uma lacuna real foi encontrada na revisão de código da Fase 2: os serviços novos
configuravam `jwk-set-uri` (valida assinatura e expiração), mas não configuravam um
`issuer-uri` (validação de emissor) — só o decoder usado nos testes fazia essa checagem.
O risco era baixo (só existe um emissor no sistema), mas era uma divergência entre o
que o registro de ameaças da fase declarava e o que estava em produção.

Essa lacuna **já foi corrigida** (quick task 260923-tj9, que fecha T-03-02/WR-07): hoje
os 4 resource servers declaram

```yaml
issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:orderflow-auth-service}
```

Repare que o valor é um texto literal (`orderflow-auth-service`), não uma URL — igual à
constante `ISSUER` do `JwtIssuerConfig` do `auth-service`. Como o `jwk-set-uri` também
está definido, o Spring Boot **não** faz descoberta OIDC (não tenta buscar nada nesse
"endereço"): ele só usa o `issuer-uri` como o valor esperado do claim `iss` do token.
Detalhes em [18-oidc-claim-iss.md](18-oidc-claim-iss.md).

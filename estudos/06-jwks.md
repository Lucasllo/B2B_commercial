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
.requestMatchers("/auth/login", "/.well-known/jwks.json", "/actuator/health/**").permitAll()
```

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

## Para que serve o endpoint JWKS hoje

Na Fase 1, só existiam `auth-service` e `gateway` no projeto, e esta seção previa que os
próximos microsserviços precisariam validar os JWTs emitidos pelo `auth-service` sem ter
a chave em memória. Isso já aconteceu: `catalog-service` e `inventory-service` (Fase 2)
configuram exatamente esse `jwk-set-uri` apontando para
`http://auth-service:8081/.well-known/jwks.json` no seu `application.yml`, e o Spring
Security baixa a chave pública de lá automaticamente para conferir a assinatura dos
tokens recebidos — sem nunca precisar perguntar ativamente "esse token é válido?" ao
`auth-service` a cada requisição. `order-service` e `notification-service` (fases
futuras) vão repetir o mesmo padrão.

Uma lacuna real encontrada na revisão de código da Fase 2: os dois serviços novos
configuram `jwk-set-uri` (valida assinatura e expiração), mas não configuram um
`issuer-uri` (validação de emissor) — só o decoder usado nos testes faz essa checagem.
O risco é baixo hoje (só existe um emissor no sistema), mas é uma divergência entre o
que o registro de ameaças da fase declarava e o que está em produção.

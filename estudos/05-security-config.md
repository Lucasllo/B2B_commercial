# SecurityConfig

Arquivo: `auth-service/src/main/java/com/orderflow/auth/config/SecurityConfig.java`

## O que essa classe faz, em uma frase

É o "guarda de segurança" do `auth-service`: define quais URLs qualquer pessoa pode
acessar livremente, quais exigem estar autenticado, como as senhas são guardadas com
segurança, e como o sistema entende um token de acesso (JWT).

## `@Configuration`

Diz ao Spring: "essa classe não é uma entidade de negócio, ela **configura** coisas que
o resto da aplicação vai usar". Dentro dela, cada método marcado com `@Bean` é uma
"peça" que o Spring cria uma vez e disponibiliza para qualquer outra parte do código que
precisar dela.

## `passwordEncoder()` — como as senhas são guardadas

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

Nunca se guarda a senha "em texto puro" no banco — se alguém roubar o banco, teria
acesso a todas as senhas. Em vez disso, se guarda um **hash**: o resultado de passar a
senha por uma função matemática fácil de calcular num sentido (senha → hash), mas
praticamente impossível de reverter (hash → senha).

**BCrypt** é um algoritmo de hash específico para senhas, propositalmente "lento" (o que
dificulta um atacante tentar milhões de senhas por segundo). Esse `Bean` disponibiliza
esse mecanismo para o resto do sistema usar sempre que precisar transformar uma senha em
hash (cadastro) ou verificar se uma senha digitada corresponde ao hash guardado (login).

## `authenticationManager(...)` — quem verifica login e senha

O `AuthenticationManager` recebe "email + senha digitados" no login e responde "sim,
batem" ou "não, estão erradas".

Detalhe importante: quando alguém erra a senha, ou o email nem existe no banco, a
resposta é **exatamente a mesma** (um erro 401 genérico). Proposital — se o sistema
respondesse "email não encontrado" versus "senha errada" de formas diferentes, um
atacante poderia usar essa diferença para descobrir quais emails estão cadastrados (isso
se chama "oráculo de enumeração de usuários").

## `jwtAuthenticationConverter()` — traduzindo o "papel" do usuário

Um **JWT** (JSON Web Token) é um "crachá digital" entregue após login bem-sucedido.
Contém informações sobre o usuário e é assinado digitalmente — qualquer serviço pode
confirmar que o crachá é autêntico sem perguntar ao `auth-service` toda vez (ver
[06-jwks.md](06-jwks.md)).

Dentro do crachá existe um campo ("claim") indicando o **papel/permissão** do usuário
(ex: `"role": "ADMIN"`). O Spring Security, por padrão, espera esse dado em outro
formato (`scope`). Como este projeto usa um formato próprio (`role`, string única), esse
método ensina o Spring a ler esse formato e traduzir para o conceito interno de
"permissão" (`ROLE_ADMIN`).

## `securityFilterChain(...)` — as regras de acesso

```java
.csrf(AbstractHttpConfigurer::disable)
```
**CSRF** é um ataque que engana o navegador de um usuário logado para enviar uma ação
indesejada sem perceber — depende do servidor usar **cookies de sessão**. Como esta API
não usa cookies (usa o crachá JWT enviado manualmente), esse ataque não se aplica aqui —
por isso a proteção é desligada, deliberadamente e não por descuido.

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
Diz ao servidor: "não guarde informação de sessão entre requisições". Cada requisição se
identifica do zero (com o JWT) — API **stateless** (sem estado).

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/auth/login", "/.well-known/jwks.json", "/actuator/health/**",
                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
        .anyRequest().authenticated())
```
- `/auth/login` — liberado (precisa poder logar sem já estar logado)
- `/.well-known/jwks.json` — liberado (endereço público onde outros serviços buscam a
  chave pública para validar JWTs — precisa ser acessível sem autenticação)
- `/actuator/health/**` — liberado (endpoint de "saúde" usado pelo `docker-compose.yml`)
- `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` — liberados (ferramenta
  de desenvolvimento local do springdoc-openapi; ver
  [13-springdoc-openapi.md](13-springdoc-openapi.md) para o porquê de cada um desses quatro
  caminhos exatos, nem mais nem menos)
- qualquer outra rota — exige estar autenticado

```java
.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
```
Ativa a validação de JWT nas requisições protegidas, usando o conversor de permissões
visto acima.

## Nota histórica no comentário do topo

`WebSecurityConfigurerAdapter` era a forma antiga de configurar segurança no Spring
(estendendo essa classe). Foi **removida** nas versões atuais do Spring Security — é uma
prática explicitamente proibida no projeto. A forma moderna é declarar um `@Bean` do
tipo `SecurityFilterChain`, construído via DSL encadeado
(`.csrf(...).sessionManagement(...).authorizeHttpRequests(...)`), como visto acima.

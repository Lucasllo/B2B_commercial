# Variáveis de ambiente e valor padrão — o `${NOME:valor}` do `jwk-set-uri`

Arquivos:

- `order-service/src/main/resources/application.yml` — a linha do `jwk-set-uri` (os outros
  resource servers têm a mesma linha)
- `docker-compose.yml` — onde a variável de ambiente é definida para cada serviço

Continuação de [06-jwks.md](06-jwks.md), que explica o que é o JWKS, e de
[01-docker-compose.md](01-docker-compose.md), que explica os containers. A mesma sintaxe
aparece em [20-configuration-properties.md](20-configuration-properties.md) para os endereços
do catálogo e do auth.

A linha estudada aqui é esta:

```yaml
jwk-set-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI:http://localhost:8081/.well-known/jwks.json}
```

A resposta curta: o mesmo `order-service` roda em lugares diferentes, e em cada um o
`auth-service` tem um **endereço diferente**. A sintaxe `${...:...}` permite trocar o
endereço sem mudar o código.

## 1. Para que serve o `jwk-set-uri`?

Quando um usuário faz login, o `auth-service` entrega a ele um **token JWT**. É como um crachá
assinado. Depois, o usuário mostra esse crachá ao `order-service` a cada pedido.

O `order-service` precisa conferir se a assinatura do crachá é verdadeira. Para isso, ele
precisa da **chave pública** do `auth-service`, que funciona como o "carimbo oficial" para
comparar com a assinatura.

O `jwk-set-uri` é o **endereço onde o `order-service` vai buscar essa chave pública**. No
projeto, esse endereço é `/.well-known/jwks.json` do `auth-service`. O `order-service` busca a
chave, guarda, e daí em diante confere os tokens sozinho, sem perguntar nada ao `auth-service`
a cada requisição.

## 2. Como funciona `${NOME:valor}`

Essa sintaxe do Spring quer dizer:

> "Procure uma **variável de ambiente** chamada `NOME`. Se ela existir, use o valor dela. Se
> não existir, use o que vem **depois dos dois-pontos**."

Lendo a linha do arquivo:

- **Primeiro**, o Spring procura a variável `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`.
- **Se não achar**, usa `http://localhost:8081/.well-known/jwks.json`, que é o **valor padrão**
  (o "plano B").

Uma **variável de ambiente** é um valor que o sistema operacional (ou o Docker) entrega ao
programa na hora de iniciar, de fora do código. É como um bilhete deixado na porta: "hoje o
endereço é este".

**Analogia:** é como uma receita que diz *"use o fermento que estiver no armário; se não tiver
nenhum, use o fermento comum"*. A receita continua a mesma, só muda o ingrediente conforme a
cozinha.

## 3. Por que não escrever só `http://localhost:8081/...`?

Porque **`localhost` significa "esta mesma máquina"**, e o que é "esta mesma máquina" muda
conforme onde o serviço está rodando.

### Cenário A: rodando direto no seu computador (pela IDE)

O `auth-service` está rodando no seu computador, na porta 8081. Para o `order-service`, que
também está no seu computador, `localhost:8081` aponta para o lugar certo. Nesse caso não
existe variável de ambiente, então o Spring usa o valor padrão, e funciona.

### Cenário B: rodando no Docker Compose

Aqui cada serviço roda **dentro do seu próprio container**, e cada container é como um
**computador separado**. Para o container do `order-service`, `localhost` significa **o
próprio container do `order-service`**, e lá dentro não existe nenhum `auth-service` na porta
8081. A busca da chave falharia, e todo token seria rejeitado.

Dentro do Docker Compose, os containers se encontram pelo **nome do serviço**. Por isso o
`docker-compose.yml` define, para cada resource server:

```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://auth-service:8081/.well-known/jwks.json
```

Repare em `auth-service` no lugar de `localhost`. Nesse cenário a variável existe, então o
Spring usa o valor dela e ignora o padrão.

### Comparando

| Onde roda | A variável existe? | Endereço usado |
|---|---|---|
| Na sua máquina, pela IDE | não | `http://localhost:8081/...` (padrão) |
| No Docker Compose | sim | `http://auth-service:8081/...` |

Com o endereço fixo `http://localhost:8081/...`, o projeto funcionaria **só** na sua máquina
e **quebraria** no Docker. Para corrigir, seria preciso **editar o arquivo e gerar outro build**
para cada ambiente. Com `${...:...}`, o **mesmo arquivo e o mesmo build** servem para os dois
casos, e quem decide o endereço é quem liga o serviço.

## 4. Vantagens extras dessa forma

- **Funciona em qualquer ambiente futuro.** Se um dia o projeto for para a AWS de verdade,
  basta definir a variável com o endereço de lá, sem tocar no código.
- **Por que esse nome tão comprido?** O Spring tem uma regra chamada *relaxed binding*: a
  variável `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` corresponde à propriedade
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`. É só trocar os pontos e hifens por
  `_` e escrever tudo em maiúsculas. Por isso o nome não é arbitrário: é a "tradução" exata da
  propriedade.
- **O mesmo padrão aparece em todo o projeto.** Logo abaixo, no mesmo arquivo,
  `issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:orderflow-auth-service}`
  segue a mesma lógica. Esse valor padrão serve em qualquer ambiente, porque não é um endereço
  de rede, e por isso o `docker-compose.yml` nem define essa variável (ver
  [18-oidc-claim-iss.md](18-oidc-claim-iss.md)). Os endereços do `auth-service` e do
  `catalog-service` que o `order-service` chama também usam `${...:...}` (ver
  [20-configuration-properties.md](20-configuration-properties.md)).

## Em uma frase

O `${VARIÁVEL:padrão}` diz ao Spring "use o endereço que me passarem de fora; se ninguém
passar, use `localhost`". Isso é necessário porque, dentro do Docker, `localhost` aponta para o
próprio container, e não para o `auth-service`.

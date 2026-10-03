# `@ConfigurationProperties` — o `ClientProperties` do order-service

Arquivo: `order-service/src/main/java/com/orderflow/order/config/ClientProperties.java`
(usado por `order-service/src/main/java/com/orderflow/order/config/ClientConfig.java`).

```java
@Validated
@ConfigurationProperties(prefix = "orderflow.clients")
public record ClientProperties(
        @NotNull @Valid Downstream authService,
        @NotNull @Valid Downstream catalogService) {

    public record Downstream(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }
}
```

## O que é, em uma frase

É uma **ficha de cadastro com campos obrigatórios**: ela guarda, dentro do código Java,
os dados que o `order-service` precisa para conversar com dois outros serviços (o
`auth-service` e o `catalog-service`). Se faltar algum campo na ficha, a aplicação nem
chega a ligar.

## 1. O problema que ela resolve

Às vezes o `order-service` precisa pedir informações a outros serviços, por exemplo
"qual o preço deste produto?" ao `catalog-service`. Para isso ele precisa saber três
coisas sobre cada um:

1. **Onde ele está**: o endereço, tipo `http://localhost:8082`
2. **Quanto tempo esperar para conseguir conectar** (*connect timeout*)
3. **Quanto tempo esperar pela resposta depois de conectado** (*read timeout*)

Esses valores poderiam estar escritos direto no código Java, mas aí seria preciso
recompilar o projeto toda vez que o endereço mudasse. E ele muda: no seu computador é
`localhost`, dentro do Docker é o nome do container. Por isso os valores ficam no
`application.yml`:

```yaml
orderflow:
  clients:
    auth-service:
      base-url: ${ORDERFLOW_AUTH_SERVICE_BASE_URL:http://localhost:8081}
      connect-timeout: 2s
      read-timeout: 3s
    catalog-service:
      base-url: ${ORDERFLOW_CATALOG_SERVICE_BASE_URL:http://localhost:8082}
      connect-timeout: 2s
      read-timeout: 3s
```

A sintaxe `${ORDERFLOW_CATALOG_SERVICE_BASE_URL:http://localhost:8082}` significa: "use
a variável de ambiente `ORDERFLOW_CATALOG_SERVICE_BASE_URL` se ela existir; se não
existir, use `http://localhost:8082`". Assim o `docker-compose` pode trocar o endereço
sem mexer em nenhum arquivo. A explicação passo a passo, incluindo por que `localhost` não
funciona dentro de um container, está em
[35-variaveis-de-ambiente-e-valor-padrao.md](35-variaveis-de-ambiente-e-valor-padrao.md).

O `ClientProperties` é a **ponte** entre esse arquivo de texto e o código Java.

## 2. `@ConfigurationProperties(prefix = "orderflow.clients")`

Essa anotação diz ao Spring: "procure no `application.yml` tudo o que estiver debaixo de
`orderflow.clients` e coloque dentro desta classe". O Spring faz isso sozinho quando a
aplicação sobe. Você não escreve nenhum código para ler o arquivo.

Os nomes não precisam ser iguais: o YAML usa hífens (`auth-service`, `connect-timeout`) e
o Java usa camelCase (`authService`, `connectTimeout`). O Spring converte um no outro
automaticamente. Esse recurso se chama *relaxed binding* (ligação flexível).

| No `application.yml`              | No Java                          |
|-----------------------------------|----------------------------------|
| `orderflow.clients.auth-service`  | `authService`                    |
| `...auth-service.base-url`        | `authService().baseUrl()`        |
| `...auth-service.connect-timeout` | `authService().connectTimeout()` |

Compare com o `@Value("${...}")` visto em
[19-sqslistener-consumo.md](19-sqslistener-consumo.md): o `@Value` puxa **um valor
solto**, enquanto o `@ConfigurationProperties` puxa **um grupo inteiro** de valores
relacionados de uma vez, já organizado e com tipo certo.

## 3. `record` e o `Downstream` aninhado

A classe é um `record`, o tipo de classe só para dados que não mudam depois de criados
(explicado em [12-records-e-anotacoes.md](12-records-e-anotacoes.md)). Para configuração
isso é ótimo: ninguém consegue alterar o endereço de um serviço sem querer no meio da
execução.

`Downstream` é um **record dentro de outro record**. Como os dois serviços precisam
exatamente das mesmas três informações, em vez de repetir tudo cria-se um "molde" só,
reaproveitado duas vezes (`authService` e `catalogService`). "Downstream" é o termo usado
para "o serviço que eu chamo", ou seja, quem está rio abaixo no fluxo da requisição.

## 4. Os tipos `URI` e `Duration`

Os campos poderiam ser todos `String` ou `int`, mas foram escolhidos tipos mais
específicos:

- **`URI`** em vez de `String`: o Spring já confere se o endereço tem um formato válido
  ao ler o arquivo.
- **`Duration`** em vez de `int`: o Spring entende `2s` como "2 segundos", `500ms` como
  "500 milissegundos", `1m` como "1 minuto". Com `int`, sempre sobraria a dúvida: esse
  `2000` é em segundos ou em milissegundos?

## 5. `@Validated` + `@NotNull` + `@Valid`: falhar cedo

Esta é a parte mais importante da classe.

- **`@NotNull`**: o campo é obrigatório.
- **`@Validated`** (em cima da classe): manda o Spring conferir essas regras **no momento
  em que a aplicação liga**.
- **`@Valid`** nos campos `authService` e `catalogService`: pede que a conferência também
  **entre** no `Downstream` e cheque os três campos lá dentro. Sem ela, o Spring só
  verificaria se `authService` existe, mas não se ele tem `baseUrl` (mesmo comportamento
  de `@Valid` descrito em [12-records-e-anotacoes.md](12-records-e-anotacoes.md)).

Imagine que alguém esqueceu de preencher o `read-timeout` no YAML:

| Sem validação                                              | Com validação                                            |
|------------------------------------------------------------|----------------------------------------------------------|
| A aplicação sobe normalmente                               | A aplicação **nem sobe**                                 |
| Horas depois, um cliente faz um pedido                     | Aparece na hora uma mensagem clara: `readTimeout` não pode ser nulo |
| O programa quebra com um `NullPointerException` confuso    | Você corrige antes de qualquer usuário ser afetado       |

Esse princípio se chama **fail fast** (falhe cedo): é melhor o erro aparecer logo ao
ligar, de forma clara, do que escondido no meio do uso.

## 6. Quem usa essa classe: o `ClientConfig`

Sozinha, a classe não é ativada. Quem a liga é o `ClientConfig`:

```java
@Configuration
@EnableConfigurationProperties(ClientProperties.class)
public class ClientConfig {

    @Bean
    public RestClient authServiceRestClient(ClientProperties clientProperties) {
        return buildRestClient(clientProperties.authService());
    }

    @Bean
    public RestClient catalogServiceRestClient(ClientProperties clientProperties) {
        return buildRestClient(clientProperties.catalogService());
    }

    public static RestClient buildRestClient(Downstream downstream) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(downstream.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(downstream.readTimeout());

        return RestClient.builder()
                .baseUrl(downstream.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
```

O passo a passo:

1. `@EnableConfigurationProperties(ClientProperties.class)` manda o Spring criar o
   `ClientProperties`, preencher com o YAML e validar.
2. Os métodos `@Bean` recebem o `ClientProperties` já pronto (o Spring entrega como
   parâmetro) e montam um **`RestClient`** para cada serviço. O `RestClient` é o objeto
   que de fato faz as chamadas HTTP.
3. Dentro de `buildRestClient`, cada valor da ficha vai para o lugar certo:
   - `baseUrl` vira o endereço base de todas as chamadas daquele cliente
   - `connectTimeout` vira o limite de tempo para conectar
   - `readTimeout` vira o limite de tempo para esperar a resposta

O `buildRestClient` é `public static` de propósito: o teste `DownstreamClientsTest` usa
esse mesmo método para montar o cliente, assim o teste exercita exatamente o código de
produção, e não uma cópia que poderia ficar diferente com o tempo.

## 7. Por que os timeouts são obrigatórios

O `RestClient` do Spring **não tem timeout padrão**. Se o `catalog-service` travar, o
`order-service` ficaria esperando **para sempre**, como uma ligação em que ninguém atende
e ninguém desliga. Com várias requisições assim, todas as threads ficam presas e o
`order-service` inteiro para de responder. Esse problema já apareceu numa revisão da Fase
3 (WR-03) e virou a decisão D-41, e é por isso que a classe não aceita timeouts em branco.

## Resumindo com uma analogia

Pensa numa agenda de contatos de uma empresa. Para cada fornecedor (o `auth-service` e o
`catalog-service`) existe uma ficha com o endereço e duas regras de paciência: "se
ninguém atender em 2 segundos, desisto de ligar" e "se atenderem mas não responderem em
3 segundos, desligo". A ficha é preenchida a partir de um papel na parede (o
`application.yml`), que pode ser trocado sem reimprimir a agenda (variáveis de ambiente).
E antes do expediente começar, alguém confere se todas as fichas estão completas. Se
faltar um campo, a empresa não abre as portas até corrigir (fail fast), em vez de
descobrir o problema no meio do atendimento a um cliente.

# BOM, trem de releases e outros termos de versão

Arquivos:

- `pom.xml` (raiz) — os três BOMs importados no `<dependencyManagement>`
- `order-service/pom.xml` — exemplo de dependência declarada sem versão
- `gateway/src/main/resources/application.yml` — as rotas estáticas do Gateway

Explicação em linguagem de iniciante de cinco termos que aparecem em
[02-spring-boot-e-spring-cloud.md](02-spring-boot-e-spring-cloud.md): **BOM**,
**bleeding-edge**, **trem de releases**, **churn** e **service discovery**.

## 1. BOM (*Bill of Materials*, "lista de materiais")

### O problema

Um projeto Java usa dezenas de **bibliotecas** (código pronto feito por outras pessoas): uma
para web, uma para segurança, uma para banco de dados, e assim por diante. Cada biblioteca tem
várias **versões** (1.0, 1.1, 2.0...). E nem toda versão funciona bem com toda outra: a versão
2.0 da biblioteca A pode quebrar com a versão 1.0 da biblioteca B.

Se você tivesse que escolher a versão de cada uma à mão, seria como montar um computador
comprando cada peça em uma loja diferente, sem saber se a placa-mãe aceita o processador.

### A solução

Um **BOM** é uma **lista oficial de versões que já foram testadas juntas**. Funciona como um
kit de montagem: o fabricante diz "essas peças, nessas versões, encaixam umas nas outras,
garantido".

No projeto, o `pom.xml` da raiz importa os BOMs dentro de `<dependencyManagement>`:

```xml
<!-- BOM oficial do Spring Boot — fixa as versões de todos os starters/auto-configurações -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-dependencies</artifactId>
    <version>${spring-boot.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

`<type>pom</type>` com `<scope>import</scope>` quer dizer: "não baixe isso como biblioteca;
copie para cá a lista de versões que está dentro dele".

Por causa disso, os serviços podem pedir uma biblioteca **sem escrever a versão**. Por exemplo,
o `order-service/pom.xml` declara só:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

Não aparece `<version>`, porque o BOM já decidiu qual versão usar. O projeto importa três BOMs:
o do **Spring Boot** (`spring-boot-dependencies`), o do **Spring Cloud**
(`spring-cloud-dependencies`) e o do **Spring Cloud AWS** (`spring-cloud-aws-dependencies`).

**Vantagem:** para atualizar, você muda **um número** (a versão do BOM), e todas as
bibliotecas do kit sobem juntas, de forma compatível.

## 2. Bleeding-edge ("a ponta que sangra")

É uma expressão em inglês para **a tecnologia mais nova possível, recém-lançada**. A imagem é a
de uma lâmina tão afiada e tão "de ponta" que corta quem usa.

Usar algo bleeding-edge significa:

- ✅ ter os recursos mais novos;
- ❌ encontrar erros que ninguém descobriu ainda, ter pouca documentação e poucas respostas no
  Stack Overflow, e ver bibliotecas que ainda não se adaptaram.

**Analogia:** é comprar um celular no **dia do lançamento**. Você tem a novidade, mas também
pega os bugs da primeira versão, que só são corrigidos semanas depois.

No projeto, o Spring Boot **3.5** foi escolhido em vez do **4.0** (lançado em novembro de
2025). O 4.0 seria a escolha bleeding-edge. O 3.5 é a versão **madura**, a que as empresas
realmente usam hoje.

## 3. O trem de releases (*release train*)

Uma **release** é uma versão publicada de um software. O **trem de releases** é uma forma de
organizar lançamentos usada pelo Spring Cloud.

O Spring Cloud não é **um** projeto: é uma **família** de vários projetos (Gateway, Contract,
OpenFeign, LoadBalancer...), cada um com seu próprio número de versão. Para você não ter que
descobrir quais versões de cada um combinam entre si, o Spring Cloud junta todos num
**"trem"** com **um nome e um número só**.

**Analogia:** imagine um trem com vários vagões. Cada vagão é um projeto, e cada um tem sua
própria versão. O trem inteiro tem um nome, e todos os vagões **saem juntos da estação**,
testados para andarem acoplados. Você não compra vagão por vagão: você pega **o trem**.

No projeto, o trem é o **`2025.0.3`, apelidado de "Northfields"**:

```xml
<spring-cloud.version>2025.0.3</spring-cloud.version>
```

Esse número é a versão do BOM do Spring Cloud (`spring-cloud-dependencies`). Ou seja, o trem é
**entregue** através de um BOM. Os dois conceitos andam juntos.

Cada trem é feito para uma linha do Spring Boot: o Northfields combina com o Boot **3.5**.
Misturar um trem com a versão errada do Boot é um dos erros mais comuns e mais difíceis de
entender em projetos Spring.

O **Spring Cloud AWS** **não** está nesse trem. Ele é um projeto separado, com versão própria
(**3.4.2**) e seu próprio BOM.

## 4. Churn ("agitação", "rotatividade")

**Churn** quer dizer **mudança constante, que dá trabalho e não traz ganho real**. No contexto
de versões, é aquele esforço de ficar **adaptando o código** porque a ferramenta mudou: um
método foi renomeado, uma configuração passou a ter outro nome, uma biblioteca deixou de ser
compatível.

**Analogia:** é como uma cidade que troca o nome das ruas todo ano. Você não ganha nada com
isso, mas precisa atualizar o GPS, os cartões de visita e os endereços de entrega.

A nota 02 diz que a escolha das versões **"evita tanto tecnologia obsoleta quanto churn de
versão"**:

- **obsoleta** demais: velha, sem suporte, não impressiona numa entrevista;
- **churn** demais: tão nova que obrigaria a gastar tempo com migrações (o Spring Boot 4.0, por
  exemplo, exige trocar para o Jackson 3 e o Jakarta EE 11), e não com o que o projeto quer
  mostrar: pedidos, saga e crédito.

## 5. Service discovery ("descoberta de serviços")

### O problema

Num sistema de microsserviços, um serviço precisa chamar o outro. O Gateway, por exemplo,
precisa saber **onde** está o `order-service` para encaminhar uma requisição a ele.

Em sistemas grandes na nuvem, os serviços **mudam de endereço o tempo todo**: sobem mais cópias
quando há muito acesso, caem, reiniciam em outra máquina. Não dá para escrever o endereço fixo.

### A solução em sistemas grandes

**Service discovery** é uma espécie de **lista telefônica automática**. Ferramentas como
**Eureka** ou **Consul** fazem isso:

1. Cada serviço, ao ligar, se **registra**: "sou o `order-service` e estou no endereço X".
2. Quem quer chamar alguém **consulta a lista**: "onde está o `order-service` agora?".
3. Quando um serviço cai, ele some da lista.

**Analogia:** é a recepção de um hotel. Você não sabe em qual quarto o hóspede está, então
pergunta à recepção, e ela sabe porque cada hóspede fez check-in.

### Por que o projeto **não** usa

O OrderFlow tem **poucos serviços, sempre os mesmos e sempre no mesmo endereço**. No Docker
Compose, cada um é encontrado pelo **nome**. Por isso o Gateway tem uma **lista fixa** (as
"rotas estáticas") no `gateway/src/main/resources/application.yml`:

```yaml
uri: ${orderflow.gateway.upstream.order:http://order-service:8085}
```

É como uma **família de cinco pessoas numa casa**: não precisa de recepção de hotel, todo mundo
sabe qual é o quarto de cada um. Colocar o Eureka aqui seria complexidade sem benefício. Essa
decisão também está registrada no ADR `docs/adr/0004-sem-service-discovery-nem-config-server.md`
(ver [32-adrs.md](32-adrs.md)).

O formato `${...:...}` dessa linha é o mesmo explicado em
[35-variaveis-de-ambiente-e-valor-padrao.md](35-variaveis-de-ambiente-e-valor-padrao.md), e as
rotas do Gateway estão em [10-gateway-application-yml.md](10-gateway-application-yml.md).

## Em uma frase cada

- **BOM:** uma lista oficial de versões de bibliotecas testadas juntas, para você não escolher
  uma por uma.
- **Bleeding-edge:** a tecnologia mais nova possível, com recursos novos mas também bugs novos.
- **Trem de releases:** vários projetos do Spring Cloud lançados juntos, com um nome e um número
  só.
- **Churn:** o trabalho de ficar adaptando o código a mudanças da ferramenta, sem ganho real.
- **Service discovery:** uma "lista telefônica" automática que diz onde cada serviço está,
  desnecessária quando os serviços são poucos e fixos.

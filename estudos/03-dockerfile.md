# Dockerfile

Os quatro `Dockerfile` do projeto (`auth-service/Dockerfile`, `gateway/Dockerfile`,
`catalog-service/Dockerfile` e `inventory-service/Dockerfile`) seguem o mesmo padrão de
**build multi-stage**, já que todos são módulos do mesmo reactor Maven.

## O que é Docker e por que usar

Docker empacota uma aplicação (código Java compilado) junto com tudo que ela precisa
para rodar (Java, bibliotecas do sistema, etc.) dentro de uma "caixa" chamada
**container**. Isso resolve o clássico problema de "funciona na minha máquina mas não no
servidor" — a caixa é sempre a mesma, não importa onde rode.

Um `Dockerfile` é a receita: uma lista de passos que diz ao Docker como montar essa caixa
(chamada de **imagem**) a partir do zero.

## O que é "build multi-stage"

Imagine que para cozinhar um prato você precisa de uma cozinha cheia de panelas e
temperos, mas na hora de servir só quer o prato pronto na mesa, sem bagunça.

"Multi-stage" é isso: o Dockerfile tem duas receitas dentro de uma:

1. **Estágio `build`**: uma "cozinha completa" com tudo que é preciso para compilar o
   código Java (compilador, Maven, etc.) — pesada e cheia de ferramentas.
2. **Estágio `runtime`**: pega só o "prato pronto" (o programa já compilado) e joga fora
   toda a cozinha usada para prepará-lo.

Resultado: a imagem final que vai para produção é bem menor, porque não carrega
ferramentas de compilação que não são mais necessárias.

## JDK vs JRE

- **JDK** (Java Development Kit) = o "kit completo" para programar em Java: inclui o
  compilador (transforma código-fonte `.java` em algo executável) e o motor de execução.
- **JRE** (Java Runtime Environment) = só o "motor de execução". Não compila nada, só
  roda um programa Java já pronto.

Por isso o estágio `build` usa `...jdk-jammy` (precisa compilar) e o estágio `runtime`
usa `...jre-jammy` (só precisa rodar o programa já compilado).

## "Tag de patch fixa" (versão exata da imagem)

`eclipse-temurin:21.0.12_8-jdk-jammy` é o nome + versão exata de uma imagem pronta com
Java 21 instalado (`eclipse-temurin` é uma distribuição gratuita e confiável do Java).

O número `21.0.12_8` é uma versão **travada**. Se em vez disso fosse só
`eclipse-temurin:21`, o Docker sempre baixaria "a versão 21 mais recente disponível
hoje" — que pode mudar sem avisar, quebrando um build que funcionava ontem. Fixar a
versão exata garante builds reproduzíveis.

## Cache de camadas (por que a ordem dos `COPY` importa)

Cada linha do Dockerfile cria uma "camada". O Docker reaproveita camadas que não
mudaram desde o último build, economizando tempo.

Por isso o Dockerfile copia primeiro só o `pom.xml` (lista de dependências) e baixa as
dependências antes de copiar o código-fonte (`src/`). Baixar dependências é lento; mudar
uma linha de código é rápido. Com essa ordem, mudar código só refaz a compilação,
reaproveitando as dependências já baixadas.

## Maven, "reactor" e `-pl / -am`

**Maven** compila projetos Java e gerencia suas dependências.

Este projeto tem um **Maven multi-módulo** (o "reactor"): um `pom.xml` "pai" na raiz, e
cada microsserviço (`auth-service`, `gateway`, `catalog-service`, `inventory-service`) é
um módulo filho com seu próprio `pom.xml`.

`-pl auth-service -am` diz ao Maven: "compile só o módulo `auth-service`, mas também
compile (`-am` = "also make") qualquer módulo do qual ele dependa" — evita compilar
módulos desnecessários.

## "Fat jar" (jar gordo) vs jar comum

Um `.jar` é como um `.zip` com código Java compilado.

- Jar comum: só o código do projeto.
- **Jar gordo** (fat jar / executável): código + todas as bibliotecas externas
  empacotadas juntas — roda sozinho com `java -jar app.jar`.

O build gera dois jars: `auth-service.jar` (plano, usado internamente pelos testes de
integração) e `auth-service-exec.jar` (gordo, o único que roda standalone). O Dockerfile
copia o `-exec.jar`.

## `HEALTHCHECK` e por que instalar `curl`

Um `HEALTHCHECK` é uma verificação periódica: "essa aplicação está realmente
funcionando?". Definida no `docker-compose.yml`, faz uma requisição HTTP para algo como
`http://localhost:8081/actuator/health`.

`curl` é o programa usado para fazer essa requisição. A imagem enxuta (`jre-jammy`) não
vem com `curl` por padrão, então o Dockerfile instala manualmente.

## Usuário não-root

O usuário "root" pode fazer qualquer coisa na máquina — inclusive coisas destrutivas.
Rodar como root dentro de um container é um risco: se alguém explorar uma falha na
aplicação, ganha controle total do container.

Por isso o Dockerfile cria um usuário comum (`groupadd`/`useradd`) e faz a aplicação
rodar como esse usuário (`USER orderflow`), dando à aplicação só o poder mínimo
necessário.

## `ENTRYPOINT`

Comando executado automaticamente quando o container liga: `java -jar app.jar`, ou seja,
"execute o programa Java empacotado nesse arquivo".

## Por que não usar Buildpacks

O projeto optou por `Dockerfile` explícito multi-stage em vez do plugin nativo do Spring
Boot (`spring-boot:build-image`, Cloud Native Buildpacks). Isso dá controle total sobre a
imagem base, o usuário não-root e a instalação do `curl` — mais transparente para fins
didáticos do que uma imagem gerada "magicamente".

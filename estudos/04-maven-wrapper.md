# Maven Wrapper (mvnw / mvnw.cmd)

## O que é o Maven

**Maven** é a ferramenta que compila o código Java do projeto e baixa automaticamente as
bibliotecas externas que ele usa (Spring Boot, Lombok, etc.).

O problema: para usar o Maven, alguém precisaria **instalar** ele manualmente na
máquina. Se cada desenvolvedor instalar uma versão diferente, o build pode se comportar
de forma diferente em cada máquina ("na minha funciona, na sua não").

## O que é o "Maven Wrapper" (`mvnw`)

`mvnw` significa **Maven Wrapper** ("embrulho" do Maven). É um script que, na primeira
vez que é executado:

1. Verifica se já existe, numa pasta escondida do projeto, a versão exata do Maven que o
   projeto precisa.
2. Se não existir, **baixa automaticamente** essa versão exata da internet (o arquivo
   `.mvn/wrapper/maven-wrapper.properties` trava a versão em `3.9.9`, baixada de
   `repo.maven.apache.org`).
3. Depois disso, usa essa versão baixada para rodar o comando Maven pedido.

Ou seja: **nunca é preciso instalar o Maven manualmente**. Basta ter o `mvnw` no
projeto.

## Por que existem dois arquivos: `mvnw` e `mvnw.cmd`

São o mesmo "embrulho", em duas linguagens de script diferentes, porque sistemas
operacionais diferentes entendem tipos de script diferentes:

- **`mvnw`** — script para Linux/macOS (e Git Bash no Windows), na linguagem do terminal
  Unix (`sh`/`bash`). Rodado como `./mvnw`.
- **`mvnw.cmd`** — equivalente para o Prompt de Comando / PowerShell do Windows nativo.
  Rodado como `mvnw.cmd` (ou só `mvnw` no CMD).

Isso garante que qualquer pessoa — Windows, Linux ou Mac — consiga compilar o projeto
sem se preocupar com o sistema operacional.

## Onde isso aparece no projeto

Usado nos `Dockerfile`:

```
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -pl auth-service -am dependency:go-offline
```

- `COPY mvnw pom.xml ./` — copia o script wrapper para dentro da imagem Docker
- `chmod +x mvnw` — dá permissão de execução ao arquivo (no Linux, um script não roda
  por padrão até isso ser explicitamente permitido)
- `./mvnw -B ...` — executa o Maven **através do wrapper**, em vez de assumir que o
  Maven já está instalado na imagem

Isso importa porque a imagem Docker (`eclipse-temurin:...-jdk-jammy`) vem só com Java
instalado, **não** com Maven. Em vez de instalar o Maven manualmente no Dockerfile, o
projeto usa o `mvnw`, que resolve isso sozinho.

## Resumindo a vantagem

Sem o wrapper, todo novo desenvolvedor (ou o CI do GitHub Actions, ou o Docker)
precisaria ter o Maven certo pré-instalado. Com `mvnw`/`mvnw.cmd`, o projeto carrega
consigo a própria ferramenta de build — só precisa ter Java instalado, rodar
`./mvnw verify`, e tudo mais acontece automaticamente, sempre com a mesma versão exata
do Maven (travada em `3.9.9`), garantindo builds consistentes para todo mundo.

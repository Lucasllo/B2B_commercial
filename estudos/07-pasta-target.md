# Pasta `target`

## O que é, em uma frase

É a **pasta de "rascunho"/saída** que o Maven cria automaticamente sempre que você
compila o projeto — tudo que é *gerado* a partir do seu código-fonte (arquivos `.class`,
jars, relatórios de teste) vai parar ali. Ela não é escrita à mão por você; é sempre
recriada pela ferramenta.

## Analogia

Pense no código-fonte (`src/`) como a receita de um bolo, escrita à mão. A pasta
`target/` é a cozinha depois que você assou o bolo: contém o bolo pronto, as sobras, os
testes de sabor que você fez no caminho. Se você jogar tudo fora e assar de novo a
partir da mesma receita, obtém exatamente o mesmo resultado — por isso ninguém precisa
*guardar* a cozinha suja, só a receita.

## O que tem dentro, no `auth-service/target/`

- **`auth-service.jar`** e **`auth-service-exec.jar`** — os dois jars explicados em
  [03-dockerfile.md](03-dockerfile.md): o jar "plano" (código só do projeto) e o jar
  "gordo" (código + todas as dependências, o que roda sozinho com `java -jar`). É
  exatamente o `auth-service-exec.jar` que o `Dockerfile` copia para dentro da imagem
  Docker.
- **`classes/`** — os arquivos `.class`, resultado de compilar cada arquivo `.java` (o
  computador não executa código-fonte `.java` diretamente; primeiro ele precisa ser
  traduzido para `.class`, o "bytecode" que a máquina virtual Java entende).
- **`test-classes/`** — a mesma ideia, mas para o código de teste (`*Test.java`,
  `*IT.java`).
- **`surefire-reports/`** — relatórios de quais testes unitários (`*Test.java`) passaram
  ou falharam.
- **`failsafe-reports/`** — a mesma coisa, mas para os testes de integração (`*IT.java`)
  — gerado pelo `maven-failsafe-plugin` configurado no `pom.xml` raiz.
- **`generated-sources/`** e **`generated-test-sources/`** — código Java gerado
  automaticamente por alguma ferramenta durante o build (por exemplo, Lombok ou
  MapStruct geram código Java a partir de anotações).

## Por que ela existe separada do código-fonte

O código-fonte (`src/`) é o que você escreve e edita manualmente — é o que importa de
verdade e o que fica no controle de versão (Git). A pasta `target/` é **derivada**: a
qualquer momento, rodar `./mvnw clean package` de novo recria ela do zero, byte a byte
igual (assumindo o mesmo código-fonte).

Justamente por ser 100% recriável, essa pasta:

1. **Não é versionada no Git** — está listada no `.gitignore` (`target/` na raiz).
   Guardar arquivos gerados no Git seria como tirar fotos da cozinha suja depois de cada
   bolo assado: ocupa espaço à toa e nunca é a "fonte da verdade" — o bolo pode sempre
   ser refeito a partir da receita.
2. **Pode ser apagada a qualquer momento sem medo** — o comando `./mvnw clean`
   literalmente apaga a pasta `target/` inteira. Não há perda real, porque tudo ali é
   reconstruível a partir do `src/`.

## Onde isso conecta com o Dockerfile

Em [03-dockerfile.md](03-dockerfile.md), o `Dockerfile` faz:

```dockerfile
RUN ./mvnw -B -pl auth-service -am package -DskipTests
...
COPY --from=build /workspace/auth-service/target/auth-service-exec.jar app.jar
```

O comando `package` do Maven é justamente o que **gera** a pasta `target/` (com os jars
dentro), e a linha seguinte só **copia um arquivo específico de dentro dela** para a
imagem final — reforçando que `target/` é puramente um resultado intermediário do
processo de build, não algo que "faz parte" do projeto de forma permanente.

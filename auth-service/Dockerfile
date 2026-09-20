# syntax=docker/dockerfile:1
# Build multi-stage do auth-service. Tag de patch fixa (eclipse-temurin 21.0.12_8), confirmada
# via Docker Hub API em 2026-09-18 — nenhuma tag flutuante (ROADMAP Fase 1, critério 1).

FROM eclipse-temurin:21.0.12_8-jdk-jammy AS build
WORKDIR /workspace

# Copia primeiro apenas o necessário para resolver dependências — aproveita cache de camada
# quando só o código-fonte muda entre builds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY auth-service/pom.xml auth-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl auth-service -am dependency:go-offline

COPY auth-service/src auth-service/src
RUN ./mvnw -B -pl auth-service -am package -DskipTests

FROM eclipse-temurin:21.0.12_8-jre-jammy AS runtime
# curl é exigido pelo HEALTHCHECK do docker-compose (plano 01-03)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system orderflow && useradd --system --gid orderflow orderflow
USER orderflow

WORKDIR /app
# auth-service-exec.jar (classifier "exec") é o jar gordo executável — auth-service.jar plano
# (sem classifier) permanece o artefato principal para não confundir o classpath de testes de
# integração do maven-failsafe-plugin (ver comentário no pom.xml raiz).
COPY --from=build /workspace/auth-service/target/auth-service-exec.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]

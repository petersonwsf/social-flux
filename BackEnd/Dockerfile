# syntax=docker/dockerfile:1

# Backend LEGADO (Javalin). Será substituído domínio a domínio pelos novos serviços Spring.
# Toda a configuração vem de variáveis de ambiente (ver PropertiesUtil): nenhum segredo entra na imagem.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/Backend-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# Imagens enviadas pelos usuários (caminho relativo usado pelo código legado). Montar um volume aqui.
RUN mkdir -p src/main/resources/public/images && chown -R app:app /app
USER app

EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

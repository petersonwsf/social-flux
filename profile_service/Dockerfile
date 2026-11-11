# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/avatars && chown -R app:app /data
WORKDIR /app
COPY --from=build /build/target/profile-service-*.jar app.jar
USER app

VOLUME /data/avatars
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8082/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

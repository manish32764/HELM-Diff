# ── build ──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ── run ──
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/helm-compare-1.0.0.jar app.jar

ENV SERVER_PORT=32764 \
    HELMCOMPARE_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 32764

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fs http://localhost:${SERVER_PORT}/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

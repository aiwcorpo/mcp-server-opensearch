# Build the jar first (./mvnw -B verify), then: docker build -t mcp-opensearch:dev .
# Not an Alpine/musl base: mcp-cedar-entities loads the native cedar-java library, built for glibc.
FROM eclipse-temurin:25-jre@sha256:78f1f183913482ef390c2722f46c3f0ce76ada341d69590e7c482ec943ee72d5

LABEL org.opencontainers.image.source="https://github.com/aiwcorpo/mcp-server-opensearch" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN groupadd --system mcp && useradd --system --gid mcp --no-create-home --home-dir /app mcp
WORKDIR /app
COPY --chown=root:root mcp-app/target/mcp-opensearch.jar /app/app.jar

USER mcp
# mTLS from the gateway only (C3); certificates and secrets come from the environment and mounted files.
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

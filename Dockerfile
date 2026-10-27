# Build the jar first (./mvnw -B verify), then: docker build -t mcp-opensearch:dev .
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048

RUN addgroup -S mcp && adduser -S -G mcp -H -h /app mcp
WORKDIR /app
COPY --chown=root:root mcp-app/target/mcp-opensearch.jar /app/app.jar

USER mcp
# mTLS from the gateway only (C3); certificates and secrets come from the environment and mounted files.
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

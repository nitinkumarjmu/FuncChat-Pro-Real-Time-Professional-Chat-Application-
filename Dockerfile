# ─────────────────────────────────────────────────────────────────────────────
# FuncChat Pro — Dockerfile
# Build: docker build -t funcchat-pro .
# Run:   docker run -p 8080:8080 -p 8081:8081 --env-file .env funcchat-pro
# ─────────────────────────────────────────────────────────────────────────────

FROM eclipse-temurin:17-jre-alpine

# Create app directory
WORKDIR /app

# Copy the fat JAR built by Maven
COPY target/funcchat-pro-server.jar app.jar

# Create logs directory
RUN mkdir -p logs

# Expose WebSocket port and webhook HTTP port
EXPOSE 8080 8081

# JVM tuning for container environment
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

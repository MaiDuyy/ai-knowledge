# --- Stage Builder ---
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (for caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -DskipTests

# --- Stage Production ---
FROM eclipse-temurin:21-jre-jammy AS production

WORKDIR /app

# Create a non-root user for security
RUN apt-get update && apt-get install -y --no-install-recommends wget && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system spring && useradd --system --gid spring --create-home spring

# Create uploads directory and change ownership of /app to spring
RUN mkdir -p /app/uploads && chown -R spring:spring /app

USER spring

# Copy the jar from builder stage
COPY --chown=spring:spring --from=builder /app/target/*.jar app.jar

# Expose the application port
EXPOSE 8080

# Environment variables with defaults
ENV DB_HOST=localhost \
    DB_PORT=5432 \
    DB_NAME=ott_chat \
    DB_USER=ott_user \
    DB_PASSWORD=ott_password \
    SPRING_PROFILES_ACTIVE=prod

# Healthcheck
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

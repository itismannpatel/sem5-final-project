# Single-image build: Vite bundle -> Spring Boot jar -> small JRE runtime.
# Temporary deployment target: Render Free, SQLite on the ephemeral disk.

FROM node:20-alpine AS frontend
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend backend
COPY --from=frontend /app/dist backend/src/main/resources/static
RUN cd backend && mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=backend /build/backend/target/twofold-backend-*.jar /app/app.jar
RUN mkdir -p /app/data \
    && useradd --system --uid 10001 --home-dir /app twofold \
    && chown -R twofold:twofold /app
USER twofold
ENV TWOFOLD_DB=/app/data/twofold.db \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

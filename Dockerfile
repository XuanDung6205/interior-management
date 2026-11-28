FROM node:24-alpine AS frontend-base
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
FROM frontend-base AS frontend-build
RUN npm run build
FROM frontend-base AS frontend-tests
CMD ["npm", "test"]

FROM maven:3.9-eclipse-temurin-21 AS java-base
ENV MAVEN_OPTS="-Xmx768m"
WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src/ src/
COPY docs/database/ docs/database/
FROM java-base AS java-tests
CMD ["mvn", "--batch-mode", "--no-transfer-progress", "test"]
FROM java-base AS java-compiled
RUN mvn --batch-mode --no-transfer-progress -Pcommerce-release -DskipTests test-compile
FROM java-compiled AS java-build
COPY --from=frontend-build /frontend/dist/ frontend/dist/
RUN mvn --batch-mode --no-transfer-progress -Pcommerce-release -DskipTests package

FROM python:3.13-slim AS tools
WORKDIR /workspace
RUN pip install --no-cache-dir PyMySQL==1.1.2 bcrypt==4.3.0
COPY scripts/ scripts/
COPY docs/database/ docs/database/
COPY frontend/public/images/catalog/ frontend/public/images/catalog/
ENTRYPOINT ["python", "scripts/tv2_docker.py"]

FROM eclipse-temurin:21-jre-alpine AS app
WORKDIR /app
RUN addgroup -S commerce && adduser -S commerce -G commerce
COPY --from=java-build --chown=commerce:commerce /workspace/target/commerce-release-build/eshop-0.0.1-SNAPSHOT.jar app.jar
USER commerce
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 CMD wget -q -O /dev/null http://127.0.0.1:8080/api/commerce/runtime || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

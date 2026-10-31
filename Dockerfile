# Stage 1: Build with Maven & JDK 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Lightweight JRE 21 Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ENV DATA_DIR=/app/data
RUN mkdir -p /app/data
VOLUME ["/app/data"]
COPY --from=build /app/target/district-ticket-monitor-2.0.0.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

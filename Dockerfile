# -------- Stage 1: Build with Maven --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy wrapper + permissions
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw   # <-- add this

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src src
RUN ./mvnw clean package -DskipTests

# -------- Stage 2: Runtime --------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java","-jar","app.jar"]

FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src
COPY webapp ./webapp

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/codeinsight-1.0-shaded.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl IdentityService -am -DskipTests package
FROM eclipse-temurin:21-jre
COPY --from=build /workspace/IdentityService/target/identity-service-0.0.1-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]

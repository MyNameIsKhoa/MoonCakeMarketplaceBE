FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -pl CommerceService -am -DskipTests package
FROM eclipse-temurin:21-jre
COPY --from=build /workspace/CommerceService/target/commerce-service-0.0.1-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]

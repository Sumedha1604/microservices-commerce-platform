FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared ./shared
COPY services ./services
COPY tests ./tests
RUN --mount=type=cache,target=/root/.m2 mvn -pl shared/common-core install -DskipTests \
    && mvn -f services/user-service/pom.xml package spring-boot:repackage -DskipTests
FROM eclipse-temurin:21-jre
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=build /workspace/services/user-service/target/user-service-*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]

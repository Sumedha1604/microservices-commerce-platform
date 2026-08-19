FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared ./shared
COPY services ./services
COPY tests ./tests
RUN mvn -pl services/product-service -am package -DskipTests
FROM eclipse-temurin:21-jre
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=build /workspace/services/product-service/target/product-service-*.jar app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java","-jar","/app/app.jar"]

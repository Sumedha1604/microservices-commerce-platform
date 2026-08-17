FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared ./shared
COPY services ./services
RUN ./mvnw -pl services/auth-service -am package -DskipTests || mvn -pl services/auth-service -am package -DskipTests
FROM eclipse-temurin:21-jre
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=build /workspace/services/auth-service/target/auth-service-*.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]

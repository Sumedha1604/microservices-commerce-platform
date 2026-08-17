FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared ./shared
COPY services ./services
RUN mvn -pl services/user-service -am package -DskipTests
FROM eclipse-temurin:21-jre
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=build /workspace/services/user-service/target/user-service-*.jar app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]

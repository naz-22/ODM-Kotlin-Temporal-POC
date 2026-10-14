FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /workspace/target/signalgenerator-*.jar app.jar
USER app
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]

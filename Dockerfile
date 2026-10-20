# One image definition for all five services. Pick the module with the SERVICE build arg:
#   docker build --build-arg SERVICE=odm-service -t kotlin-ol/odm-service:local .
# docker-compose.yml does this for every service under the "apps" profile.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# The aggregator POM lists all five modules, so every module POM must be present even though
# only SERVICE is built (-pl ... -am).
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
COPY notification-service/pom.xml notification-service/
COPY odm-service/pom.xml odm-service/

ARG SERVICE
RUN test -n "$SERVICE" || (echo "Build arg SERVICE is required (e.g. odm-service)" && exit 1)

# Dependencies in their own layer (POMs only), so a source change does not re-download them.
# Works with both the classic builder and BuildKit (no cache mounts).
RUN ./mvnw -B -q -pl ${SERVICE} -am dependency:go-offline

COPY ${SERVICE}/src ${SERVICE}/src

# Tests run outside the image build (./mvnw test). The Kotlin compile daemon is not used
# inside a throwaway build container.
RUN ./mvnw -B -q -pl ${SERVICE} -am package -DskipTests -Dkotlin.compiler.daemon=false \
    && cp ${SERVICE}/target/*-SNAPSHOT.jar /workspace/app.jar


FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app

# Heap sized from the container memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Exec form: the JVM is PID 1 and receives SIGTERM directly, so `docker compose stop`
# triggers Spring Boot's graceful shutdown (Temporal workers, Solace sessions, Tomcat).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

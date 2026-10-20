FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY rook-core/pom.xml rook-core/pom.xml
COPY rook-db/pom.xml rook-db/pom.xml
COPY rook-changes/pom.xml rook-changes/pom.xml
COPY rook-codeintel/pom.xml rook-codeintel/pom.xml
COPY rook-workflow/pom.xml rook-workflow/pom.xml
COPY rook-agent/pom.xml rook-agent/pom.xml
COPY rook-verify/pom.xml rook-verify/pom.xml
COPY rook-control/pom.xml rook-control/pom.xml
COPY rook-github/pom.xml rook-github/pom.xml
COPY rook-pipeline/pom.xml rook-pipeline/pom.xml
COPY rook-testkit/pom.xml rook-testkit/pom.xml
COPY rook-eval/pom.xml rook-eval/pom.xml
COPY rook-server/pom.xml rook-server/pom.xml
COPY . .
RUN mvn -B -q -pl rook-server -am -DskipTests -DskipITs -Dspotless.check.skip=true -Djacoco.skip=true package

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache docker-cli tini \
    && addgroup -S rook && adduser -S rook -G rook
WORKDIR /app
COPY --from=build /src/rook-server/target/rook-server-*-exec.jar /app/rook.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
USER rook
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["/sbin/tini", "--", "java", "-jar", "/app/rook.jar"]

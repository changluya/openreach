# OpenReach runtime-only image
#
# Build contract:
#   1) Host: mvn clean package  -> target/openreach-*.jar
#   2) Docker: COPY that JAR into a Java 17 runtime image
#
# The Spring Boot JAR is architecture-independent, so the exact same JAR is
# reused for linux/amd64 and linux/arm64. Docker does not run Maven.

FROM eclipse-temurin:17-jre-noble
WORKDIR /app

# Numeric non-root UID/GID keeps the image runtime-only: no apt, groupadd or
# useradd step is required for either target architecture.
COPY --chown=10001:10001 target/openreach-*.jar /app/app.jar
RUN mkdir -p /app/logs && chown -R 10001:10001 /app/logs

USER 10001:10001
EXPOSE 8080

ENV JAVA_OPTS="-Xms128m -Xmx512m" \
    OPENREACH_LOG_PATH="/app/logs"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

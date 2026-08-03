# syntax=docker/dockerfile:1.7

# One reviewed build recipe serves every Java deployable. SERVICE_MODULE is a repository folder
# name, not arbitrary shell input; docker-compose.yml supplies one of the five known values.
FROM eclipse-temurin:21-jdk-jammy AS build

ARG SERVICE_MODULE
WORKDIR /workspace
COPY . .

# Compile the dependency-free health probe while the JDK is available. The runtime stage uses a
# JRE deliberately, so Java source-file mode is unavailable there (`jdk.compiler` is absent).
RUN javac -d /workspace/healthcheck infrastructure/docker/HealthCheck.java

# Windows checkouts can present mvnw with CRLF and without an executable bit. Normalize only the
# image copy, then build the selected service plus its internal library dependencies.
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -ntp -Pno-docker -pl "services/${SERVICE_MODULE}" -am package -DskipTests \
    && cp "services/${SERVICE_MODULE}/target/${SERVICE_MODULE}-0.0.1-SNAPSHOT.jar" /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 payflow \
    && useradd --system --uid 10001 --gid payflow --home-dir /opt/payflow \
        --shell /usr/sbin/nologin payflow

WORKDIR /opt/payflow
COPY --from=build --chown=payflow:payflow /workspace/app.jar /opt/payflow/app.jar
COPY --from=build --chown=payflow:payflow /workspace/healthcheck/HealthCheck.class /opt/payflow/healthcheck/HealthCheck.class

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/opt/payflow/app.jar"]

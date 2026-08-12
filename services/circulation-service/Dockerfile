# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble@sha256:735baf2edc6cd6485240144a84fa4db142b9a6f47b4eb4080f31058d200f9813 AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts settings-gradle.lockfile build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY circulation-service/build.gradle.kts circulation-service/gradle.lockfile ./circulation-service/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --quiet :circulation-service:dependencies --no-daemon

COPY circulation-service/src ./circulation-service/src

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :circulation-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS runtime
WORKDIR /app

RUN apk add --no-cache --upgrade \
        libexpat=2.8.2-r0 \
        p11-kit=0.26.2-r0 \
        p11-kit-trust=0.26.2-r0 \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && rm -f /bin/ash /bin/sh /sbin/apk /bin/busybox

COPY --from=builder --chown=10001:10001 \
    /workspace/circulation-service/build/libs/circulation-service.jar \
    /app/circulation-service.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/circulation-service.jar"]

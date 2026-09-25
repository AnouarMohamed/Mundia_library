# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble@sha256:2feab631bffce6236d8bb5261a4abe19a8d6f85bad1c01166f74686c983d011f AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts settings-gradle.lockfile build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY circulation-service/build.gradle.kts circulation-service/gradle.lockfile ./circulation-service/
COPY catalog-service/build.gradle.kts catalog-service/gradle.lockfile ./catalog-service/
COPY membership-service/build.gradle.kts membership-service/gradle.lockfile ./membership-service/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --quiet :catalog-service:dependencies --no-daemon

COPY catalog-service/src ./catalog-service/src
COPY circulation-service/src/main/proto ./circulation-service/src/main/proto

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :catalog-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8 AS runtime
WORKDIR /app

RUN apk add --no-cache --upgrade libexpat \
    && addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && rm -f /bin/ash /bin/sh /sbin/apk /bin/busybox

COPY --from=builder --chown=10001:10001 \
    /workspace/catalog-service/build/libs/catalog-service.jar \
    /app/catalog-service.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/catalog-service.jar"]

# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS builder
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

RUN addgroup -S mundia && adduser -S -G mundia -u 10001 mundia

COPY --from=builder --chown=mundia:mundia \
    /workspace/circulation-service/build/libs/circulation-service.jar \
    /app/circulation-service.jar

USER 10001:10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/circulation-service.jar"]

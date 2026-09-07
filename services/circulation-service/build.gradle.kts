import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jooq.codegen)
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        allWarningsAsErrors = true
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.google.protobuf:protobuf-java:${libs.versions.protobuf.java.get()}")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.apache.kafka:kafka-clients")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // Spring Boot 4.1.0 manages 42.7.11, which is affected by
    // CVE-2026-54291. Keep this explicit until the managed BOM is updated.
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    jooqCodegen("org.jooq:jooq-meta-extensions:${libs.versions.jooq.get()}")
    jooqCodegen("com.h2database:h2:2.4.240")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.java.get()}"
    }
    // Spring Boot configures the grpc generator when it reacts to the
    // protobuf plugin. This contract currently defines messages only, but the
    // generator artifact must still be exact rather than resolving `null`.
    plugins {
        maybeCreate("grpc").artifact =
            "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.java.get()}"
    }
}

jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.JavaGenerator"
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                includes = "circulation_.*|outbox_event"
                excludes = "flyway_schema_history"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            generate {
                isDeprecated = false
                isRecords = true
                isPojos = false
                isDaos = false
            }
            target {
                packageName =
                    "com.mundiapolis.library.circulation.adapter.outbound.persistence.jooq.generated"
                directory = layout.buildDirectory.dir("generated-src/jooq/main").get().asFile.path
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.files(fileTree("src/main/resources/db/migration"))
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(tasks.named("jooqCodegen"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(tasks.named("bootJar"))
    environment("SPRING_FLYWAY_ENABLED", "true")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "circulation-service.jar"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    environment(
        "SPRING_FLYWAY_ENABLED",
        System.getenv("SPRING_FLYWAY_ENABLED") ?: "true",
    )
}

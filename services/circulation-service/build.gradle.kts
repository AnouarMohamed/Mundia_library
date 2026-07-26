import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jooq.codegen)
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
    implementation("io.micrometer:micrometer-registry-prometheus")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    jooqCodegen("org.jooq:jooq-meta-extensions:${libs.versions.jooq.get()}")
    jooqCodegen("com.h2database:h2:2.4.240")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "circulation-service.jar"
}

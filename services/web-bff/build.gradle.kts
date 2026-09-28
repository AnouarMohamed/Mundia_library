import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "web-bff.jar"
}

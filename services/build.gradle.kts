plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.jooq.codegen) apply false
}

allprojects {
    group = "com.mundiapolis.library"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

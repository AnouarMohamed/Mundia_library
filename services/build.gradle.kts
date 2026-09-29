plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.jooq.codegen) apply false
    alias(libs.plugins.protobuf) apply false
}

allprojects {
    group = "com.mundiapolis.library"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.apache.tomcat.embed") {
                useVersion("11.0.25")
                because("Tomcat 11.0.24 and earlier contain critical authentication and access-control vulnerabilities")
            }
            if (
                requested.group == "tools.jackson.core" &&
                requested.name == "jackson-databind"
            ) {
                useVersion("3.1.6")
                because("Jackson 3.1.5 is affected by CVE-2026-68497")
            }
        }
    }
}

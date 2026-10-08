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
            if (requested.group == "tools.jackson.core") {
                useVersion("3.1.7")
                because("Jackson 3.1.6 is affected by multiple high-severity denial-of-service vulnerabilities")
            }
            if (requested.group == "at.yawk.lz4" && requested.name == "lz4-java") {
                useVersion("1.11.4")
                because("lz4-java 1.10.1 is affected by CVE-2026-106451")
            }
        }
    }
}

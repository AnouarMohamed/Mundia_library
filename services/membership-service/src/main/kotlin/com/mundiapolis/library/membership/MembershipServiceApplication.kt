package com.mundiapolis.library.membership

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MembershipServiceApplication

fun main(args: Array<String>) {
    runApplication<MembershipServiceApplication>(*args)
}

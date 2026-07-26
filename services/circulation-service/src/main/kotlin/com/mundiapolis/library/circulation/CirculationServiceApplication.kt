package com.mundiapolis.library.circulation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CirculationServiceApplication

fun main(args: Array<String>) {
    runApplication<CirculationServiceApplication>(*args)
}

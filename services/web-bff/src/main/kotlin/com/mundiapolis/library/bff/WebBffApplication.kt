package com.mundiapolis.library.bff

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WebBffApplication

fun main(args: Array<String>) {
    runApplication<WebBffApplication>(*args)
}

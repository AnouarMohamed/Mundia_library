package com.mundiapolis.library.membership.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

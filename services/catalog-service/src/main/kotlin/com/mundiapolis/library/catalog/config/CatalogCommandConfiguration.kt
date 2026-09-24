package com.mundiapolis.library.catalog.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class CatalogCommandConfiguration {
    @Bean
    fun catalogClock(): Clock = Clock.systemUTC()
}

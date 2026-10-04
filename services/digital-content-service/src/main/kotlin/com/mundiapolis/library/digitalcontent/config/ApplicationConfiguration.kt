package com.mundiapolis.library.digitalcontent.config

import com.mundiapolis.library.digitalcontent.service.DigitalAssetReader
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    @Bean
    fun digitalContentService(reader: DigitalAssetReader, clock: Clock) =
        DigitalContentService(reader, clock)
}

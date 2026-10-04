package com.mundiapolis.library.digitalcontent.config

import com.mundiapolis.library.digitalcontent.service.DigitalAssetReader
import com.mundiapolis.library.digitalcontent.service.CloudFrontDownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.DisabledDownloadUrlSigner
import com.mundiapolis.library.digitalcontent.service.DigitalContentService
import com.mundiapolis.library.digitalcontent.service.DownloadUrlSigner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    @Bean
    fun downloadUrlSigner(properties: CloudFrontProperties): DownloadUrlSigner =
        if (properties.enabled) CloudFrontDownloadUrlSigner(properties) else DisabledDownloadUrlSigner()

    @Bean
    fun digitalContentService(
        reader: DigitalAssetReader,
        signer: DownloadUrlSigner,
        clock: Clock,
    ) = DigitalContentService(reader, signer, clock)
}

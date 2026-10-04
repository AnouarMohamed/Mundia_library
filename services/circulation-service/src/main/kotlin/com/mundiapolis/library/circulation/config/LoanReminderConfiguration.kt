package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.application.model.LoanReminderType
import com.mundiapolis.library.circulation.application.service.LoanReminderService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.loan-reminder", name = ["enabled"], havingValue = "true")
class LoanReminderConfiguration {
    @Bean
    fun loanReminderScheduler(
        service: LoanReminderService,
        properties: LoanReminderProperties,
        meterRegistry: MeterRegistry,
    ): LoanReminderScheduler = LoanReminderScheduler(service, properties, meterRegistry)
}

class LoanReminderScheduler(
    private val service: LoanReminderService,
    private val properties: LoanReminderProperties,
    meterRegistry: MeterRegistry,
) {
    private val emittedCounters = LoanReminderType.entries.associateWith { type ->
        meterRegistry.counter("mundia.loan.reminder.emitted", "type", type.name.lowercase())
    }
    private val failureCounters = LoanReminderType.entries.associateWith { type ->
        meterRegistry.counter("mundia.loan.reminder.failed", "type", type.name.lowercase())
    }

    @Scheduled(fixedDelayString = "\${app.loan-reminder.poll-interval}")
    fun emitReminders() {
        LoanReminderType.entries.forEach { type ->
            try {
                val emitted = service.emitEligible(
                    type,
                    properties.dueSoonLeadTime,
                    properties.batchSize,
                )
                emittedCounters.getValue(type).increment(emitted.toDouble())
            } catch (exception: RuntimeException) {
                failureCounters.getValue(type).increment()
                LOGGER.error("Loan reminder batch emission failed for type {}", type, exception)
            }
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(LoanReminderScheduler::class.java)
    }
}

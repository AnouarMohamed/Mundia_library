package com.mundiapolis.library.notification.service

import com.mundiapolis.library.notification.dto.SesFeedbackEvent
import com.mundiapolis.library.notification.dto.SesFeedbackExecution
import java.time.Clock

fun interface SesFeedbackStore {
    fun apply(event: SesFeedbackEvent, receivedAt: java.time.Instant): SesFeedbackExecution
}

class SesFeedbackService(
    private val store: SesFeedbackStore,
    private val clock: Clock,
) {
    fun apply(event: SesFeedbackEvent): SesFeedbackExecution = store.apply(event, clock.instant())
}

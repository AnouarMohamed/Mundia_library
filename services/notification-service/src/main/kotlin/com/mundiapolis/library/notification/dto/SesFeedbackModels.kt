package com.mundiapolis.library.notification.dto

import java.time.Instant
import java.util.UUID

enum class SesFeedbackType(val providerOutcome: String, val precedence: Int) {
    SEND("SENT", 10),
    DELIVERY_DELAY("DELAYED", 20),
    DELIVERY("DELIVERED", 30),
    OPEN("DELIVERED", 30),
    CLICK("DELIVERED", 30),
    REJECT("REJECTED", 40),
    RENDERING_FAILURE("RENDERING_FAILED", 40),
    BOUNCE("BOUNCED", 50),
    COMPLAINT("COMPLAINED", 60),
}

enum class EmailSuppressionReason(val precedence: Int) {
    PERMANENT_BOUNCE(10),
    COMPLAINT(20),
}

data class SesFeedbackEvent(
    val snsMessageId: UUID,
    val deliveryId: UUID,
    val providerMessageReference: String,
    val type: SesFeedbackType,
    val eventAt: Instant,
    val payloadSha256: String,
    val suppressionReason: EmailSuppressionReason? = null,
)

data class SesFeedbackExecution(val replayed: Boolean)

class SesFeedbackContractException(message: String) : RuntimeException(message)
class SesFeedbackTransientException(message: String) : RuntimeException(message)
class SesFeedbackConflictException : RuntimeException("SES feedback replay conflicts with its receipt")
class SesFeedbackCorrelationException : RuntimeException("SES feedback does not match a delivered email")

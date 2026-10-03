package com.mundiapolis.library.notification.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class ReplayDeadLetterRequest(
    @field:NotBlank
    @field:Size(min = 20, max = 500)
    val justification: String,
)

data class DeadLetterReplay(
    val requestId: String,
    val deliveryId: String,
    val replayCount: Int,
    val queuedAt: Instant,
)

package com.mundiapolis.library.notification.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class RemoveEmailSuppressionRequest(
    @field:NotBlank
    @field:Size(min = 20, max = 500)
    val justification: String,
)

data class EmailSuppressionRemoval(
    val requestId: String,
    val memberId: String,
    val removed: Boolean,
    val previousReason: EmailSuppressionReason?,
    val performedAt: Instant,
)

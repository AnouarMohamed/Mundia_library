package com.mundiapolis.library.circulation.application.model

import java.time.Instant

data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetsAt: Instant,
)

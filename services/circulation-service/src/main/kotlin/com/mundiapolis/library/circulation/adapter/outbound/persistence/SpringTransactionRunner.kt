package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
) : TransactionRunner {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun <T : Any> required(block: () -> T): T =
        requireNotNull(transactionTemplate.execute { block() }) {
            "Transaction completed without a command result"
        }
}

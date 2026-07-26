package com.mundiapolis.library.circulation.adapter.outbound.persistence

import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    transactionManager: PlatformTransactionManager,
    private val dsl: DSLContext,
) : TransactionRunner {
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        timeout = TRANSACTION_TIMEOUT_SECONDS
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    override fun <T : Any> required(block: () -> T): T =
        requireNotNull(
            transactionTemplate.execute {
                dsl.execute(
                    """
                    SELECT
                        set_config('lock_timeout', '$LOCK_TIMEOUT', true),
                        set_config('statement_timeout', '$STATEMENT_TIMEOUT', true),
                        set_config(
                            'idle_in_transaction_session_timeout',
                            '$IDLE_TRANSACTION_TIMEOUT',
                            true
                        )
                    """.trimIndent(),
                )
                block()
            },
        ) {
            "Transaction completed without a command result"
        }

    private companion object {
        const val TRANSACTION_TIMEOUT_SECONDS = 10
        const val LOCK_TIMEOUT = "3s"
        const val STATEMENT_TIMEOUT = "10s"
        const val IDLE_TRANSACTION_TIMEOUT = "10s"
    }
}

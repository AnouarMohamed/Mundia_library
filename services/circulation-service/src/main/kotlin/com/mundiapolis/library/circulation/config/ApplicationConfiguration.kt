package com.mundiapolis.library.circulation.config

import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationPolicyQuery
import com.mundiapolis.library.circulation.application.port.inbound.GetCirculationStatusQuery
import com.mundiapolis.library.circulation.application.port.outbound.ConsumerInboxStore
import com.mundiapolis.library.circulation.application.port.outbound.CopyStore
import com.mundiapolis.library.circulation.application.port.outbound.CirculationStatisticsPort
import com.mundiapolis.library.circulation.application.port.outbound.FineIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.FineLedgerStore
import com.mundiapolis.library.circulation.application.port.outbound.FineOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.FineStore
import com.mundiapolis.library.circulation.application.port.outbound.IdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.IdentifierGenerator
import com.mundiapolis.library.circulation.application.port.outbound.InventoryAuditStore
import com.mundiapolis.library.circulation.application.port.outbound.InventoryIdempotencyStore
import com.mundiapolis.library.circulation.application.port.outbound.InventoryOutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.LoanStore
import com.mundiapolis.library.circulation.application.port.outbound.MemberEligibilityStore
import com.mundiapolis.library.circulation.application.port.outbound.OutboxEventStore
import com.mundiapolis.library.circulation.application.port.outbound.TimeProvider
import com.mundiapolis.library.circulation.application.port.outbound.TransactionRunner
import com.mundiapolis.library.circulation.application.service.CirculationCommandService
import com.mundiapolis.library.circulation.application.service.FineCommandService
import com.mundiapolis.library.circulation.application.service.GetCirculationPolicyService
import com.mundiapolis.library.circulation.application.service.GetCirculationStatusService
import com.mundiapolis.library.circulation.application.service.InventoryCommandService
import com.mundiapolis.library.circulation.application.service.MembershipEligibilityService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {
    @Bean
    fun getCirculationPolicyQuery(
        policy: CirculationPolicyProperties,
    ): GetCirculationPolicyQuery = GetCirculationPolicyService(policy)

    @Bean
    fun getCirculationStatusQuery(
        statistics: CirculationStatisticsPort,
    ): GetCirculationStatusQuery = GetCirculationStatusService(statistics)

    @Bean
    fun circulationCommandService(
        transactionRunner: TransactionRunner,
        loanStore: LoanStore,
        copyStore: CopyStore,
        memberEligibilityStore: MemberEligibilityStore,
        idempotencyStore: IdempotencyStore,
        outboxEventStore: OutboxEventStore,
        timeProvider: TimeProvider,
        identifierGenerator: IdentifierGenerator,
        policy: CirculationPolicyProperties,
    ): CirculationCommandService = CirculationCommandService(
        transactionRunner = transactionRunner,
        loanStore = loanStore,
        copyStore = copyStore,
        eligibilityStore = memberEligibilityStore,
        idempotencyStore = idempotencyStore,
        outboxEventStore = outboxEventStore,
        timeProvider = timeProvider,
        identifierGenerator = identifierGenerator,
        defaultLoanPeriod = policy.defaultLoanPeriod,
        renewalPeriod = policy.renewalPeriod,
        maximumRenewals = policy.maximumRenewals,
        idempotencyRetention = policy.idempotencyRetention,
    )

    @Bean
    fun fineCommandService(
        transactionRunner: TransactionRunner,
        loanStore: LoanStore,
        fineStore: FineStore,
        fineLedgerStore: FineLedgerStore,
        fineIdempotencyStore: FineIdempotencyStore,
        fineOutboxEventStore: FineOutboxEventStore,
        timeProvider: TimeProvider,
        identifierGenerator: IdentifierGenerator,
        policy: CirculationPolicyProperties,
    ): FineCommandService = FineCommandService(
        transactionRunner = transactionRunner,
        loanStore = loanStore,
        fineStore = fineStore,
        fineLedgerStore = fineLedgerStore,
        idempotencyStore = fineIdempotencyStore,
        outboxEventStore = fineOutboxEventStore,
        timeProvider = timeProvider,
        identifierGenerator = identifierGenerator,
        currency = policy.fineCurrency,
        idempotencyRetention = policy.idempotencyRetention,
    )

    @Bean
    fun inventoryCommandService(
        transactionRunner: TransactionRunner,
        copyStore: CopyStore,
        inventoryIdempotencyStore: InventoryIdempotencyStore,
        inventoryAuditStore: InventoryAuditStore,
        inventoryOutboxEventStore: InventoryOutboxEventStore,
        timeProvider: TimeProvider,
        identifierGenerator: IdentifierGenerator,
        policy: CirculationPolicyProperties,
    ): InventoryCommandService = InventoryCommandService(
        transactionRunner = transactionRunner,
        copyStore = copyStore,
        idempotencyStore = inventoryIdempotencyStore,
        auditStore = inventoryAuditStore,
        outboxEventStore = inventoryOutboxEventStore,
        timeProvider = timeProvider,
        identifierGenerator = identifierGenerator,
        idempotencyRetention = policy.idempotencyRetention,
    )

    @Bean
    fun membershipEligibilityService(
        transactionRunner: TransactionRunner,
        memberEligibilityStore: MemberEligibilityStore,
        consumerInboxStore: ConsumerInboxStore,
        timeProvider: TimeProvider,
    ): MembershipEligibilityService = MembershipEligibilityService(
        transactionRunner = transactionRunner,
        eligibilityStore = memberEligibilityStore,
        inboxStore = consumerInboxStore,
        timeProvider = timeProvider,
        maximumFutureClockSkew = Duration.ofMinutes(5),
    )

    @Bean
    fun timeProvider(): TimeProvider = TimeProvider(Instant::now)

    @Bean
    fun identifierGenerator(): IdentifierGenerator = IdentifierGenerator(UUID::randomUUID)
}

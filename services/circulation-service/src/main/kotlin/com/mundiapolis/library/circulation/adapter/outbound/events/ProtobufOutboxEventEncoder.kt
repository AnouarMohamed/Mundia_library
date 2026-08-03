package com.mundiapolis.library.circulation.adapter.outbound.events

import com.google.protobuf.Timestamp
import com.mundiapolis.library.circulation.application.model.ClaimedOutboxEvent
import com.mundiapolis.library.circulation.application.model.EncodedOutboxEvent
import com.mundiapolis.library.circulation.application.port.outbound.EventContractEncoder
import com.mundiapolis.library.circulation.config.OutboxDeliveryProperties
import com.mundiapolis.library.circulation.contract.v1.CirculationEvent
import com.mundiapolis.library.circulation.contract.v1.CopyEvent
import com.mundiapolis.library.circulation.contract.v1.CopyStatus
import com.mundiapolis.library.circulation.contract.v1.FineEvent
import com.mundiapolis.library.circulation.contract.v1.FineLedgerEntryType
import com.mundiapolis.library.circulation.contract.v1.FineStatus
import com.mundiapolis.library.circulation.contract.v1.LoanEvent
import com.mundiapolis.library.circulation.contract.v1.LoanStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

class ProtobufOutboxEventEncoder(
    private val objectMapper: ObjectMapper,
    private val properties: OutboxDeliveryProperties,
) : EventContractEncoder {
    override fun encode(event: ClaimedOutboxEvent): EncodedOutboxEvent {
        requireContract(event.eventVersion == CONTRACT_VERSION)
        requireContract(event.aggregateVersion >= 0)
        requireContract(event.eventType in EVENT_TYPES)
        requireContract(EVENT_TYPES.getValue(event.eventType) == event.aggregateType)
        requireContract(event.traceId == null || TRACE_ID.matches(event.traceId))

        val payload = parsePayload(event.payloadJson)
        val envelope = CirculationEvent.newBuilder()
            .setEventId(event.id.toString())
            .setEventType(event.eventType)
            .setEventVersion(event.eventVersion)
            .setAggregateType(event.aggregateType)
            .setAggregateId(event.aggregateId.toString())
            .setAggregateVersion(event.aggregateVersion)
            .setOccurredAt(event.occurredAt.toTimestamp())
            .apply {
                event.traceId?.let(::setTraceId)
                when (event.aggregateType) {
                    LOAN_AGGREGATE -> setLoan(encodeLoan(event, payload))
                    FINE_AGGREGATE -> setFine(encodeFine(event, payload))
                    COPY_AGGREGATE -> setCopy(encodeCopy(event, payload))
                    else -> throw OutboxContractException()
                }
            }
            .build()
        val bytes = envelope.toByteArray()
        if (bytes.size > properties.maximumEventBytes) {
            throw OutboxPayloadTooLargeException()
        }

        return EncodedOutboxEvent(
            eventId = event.id,
            key = event.aggregateId.toString(),
            eventType = event.eventType,
            eventVersion = event.eventVersion,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            aggregateVersion = event.aggregateVersion,
            occurredAt = event.occurredAt,
            schemaSubject = properties.schemaSubject,
            schemaVersion = CONTRACT_VERSION,
            payload = bytes,
        )
    }

    private fun encodeLoan(event: ClaimedOutboxEvent, payload: JsonNode): LoanEvent {
        requireContract(payload.isObject)
        requireContract(payload.propertyNames().all(LOAN_PAYLOAD_FIELDS::contains))

        val loanId = payload.requiredUuid("loanId")
        val status = payload.requiredText("status").toLoanStatus()
        val loanVersion = payload.requiredNonNegativeLong("loanVersion")
        requireContract(loanId == event.aggregateId)
        requireContract(loanVersion == event.aggregateVersion)
        requireContract(status in LOAN_EVENT_STATUSES.getValue(event.eventType))
        payload.validateActorFingerprint()

        return LoanEvent.newBuilder()
            .setLoanId(loanId.toString())
            .setMemberId(payload.requiredUuid("memberId").toString())
            .setEditionId(payload.requiredUuid("editionId").toString())
            .setStatus(status)
            .setRequestedAt(payload.requiredInstant("requestedAt").toTimestamp())
            .setRenewalCount(payload.requiredNonNegativeInt("renewalCount"))
            .setLoanVersion(loanVersion)
            .apply {
                payload.optionalUuid("copyId")?.let { setCopyId(it.toString()) }
                payload.optionalInstant("checkedOutAt")?.let { setCheckedOutAt(it.toTimestamp()) }
                payload.optionalInstant("dueAt")?.let { setDueAt(it.toTimestamp()) }
                payload.optionalInstant("returnedAt")?.let { setReturnedAt(it.toTimestamp()) }
            }
            .build()
    }

    private fun encodeFine(event: ClaimedOutboxEvent, payload: JsonNode): FineEvent {
        requireContract(payload.isObject)
        requireContract(payload.propertyNames().all(FINE_PAYLOAD_FIELDS::contains))

        val fineId = payload.requiredUuid("fineId")
        val fineVersion = payload.requiredNonNegativeLong("fineVersion")
        val entryType = payload.requiredText("ledgerEntryType").toFineLedgerEntryType()
        requireContract(fineId == event.aggregateId)
        requireContract(fineVersion == event.aggregateVersion)
        requireContract(entryType == FINE_EVENT_ENTRY_TYPES.getValue(event.eventType))
        payload.validateActorFingerprint()
        payload.validateExternalReference()

        val currency = payload.requiredText("currency")
        requireContract(CURRENCY.matches(currency))

        return FineEvent.newBuilder()
            .setFineId(fineId.toString())
            .setLoanId(payload.requiredUuid("loanId").toString())
            .setMemberId(payload.requiredUuid("memberId").toString())
            .setCurrency(currency)
            .setBalanceMinor(payload.requiredNonNegativeLong("balanceMinor"))
            .setStatus(payload.requiredText("status").toFineStatus())
            .setFineVersion(fineVersion)
            .setLedgerEntryId(payload.requiredUuid("ledgerEntryId").toString())
            .setLedgerEntryType(entryType)
            .setLedgerDeltaMinor(payload.requiredLong("ledgerDeltaMinor"))
            .build()
    }

    private fun encodeCopy(event: ClaimedOutboxEvent, payload: JsonNode): CopyEvent {
        requireContract(payload.isObject)
        requireContract(payload.propertyNames().all(COPY_PAYLOAD_FIELDS::contains))

        val copyId = payload.requiredUuid("copyId")
        val copyVersion = payload.requiredNonNegativeLong("copyVersion")
        val status = payload.requiredText("status").toCopyStatus()
        requireContract(copyId == event.aggregateId)
        requireContract(copyVersion == event.aggregateVersion)
        requireContract(status in COPY_EVENT_STATUSES.getValue(event.eventType))
        payload.validateActorFingerprint()
        val barcode = payload.requiredText("barcode")
        requireContract(BARCODE.matches(barcode))
        val reason = payload.requiredText("reason")
        requireContract(reason.length <= 500 && reason == reason.trim())

        return CopyEvent.newBuilder()
            .setCopyId(copyId.toString())
            .setEditionId(payload.requiredUuid("editionId").toString())
            .setBranchId(payload.requiredUuid("branchId").toString())
            .setBarcode(barcode)
            .setStatus(status)
            .setCopyVersion(copyVersion)
            .setReason(reason)
            .apply {
                payload.optionalText("shelfLocation")?.let { shelfLocation ->
                    requireContract(
                        shelfLocation.length <= 128 && shelfLocation == shelfLocation.trim(),
                    )
                    setShelfLocation(shelfLocation)
                }
            }
            .build()
    }

    private fun parsePayload(raw: String): JsonNode =
        try {
            objectMapper.readTree(raw) ?: throw OutboxContractException()
        } catch (_: OutboxContractException) {
            throw OutboxContractException()
        } catch (_: Exception) {
            throw OutboxContractException()
        }

    private fun JsonNode.requiredText(field: String): String {
        val value = get(field)
        requireContract(value != null && value.isString)
        val text = value.stringValue()
        requireContract(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH && text.none(Char::isISOControl))
        return text
    }

    private fun JsonNode.requiredUuid(field: String): UUID {
        val raw = requiredText(field)
        val value = runCatching { UUID.fromString(raw) }.getOrNull()
        requireContract(value != null && value.toString() == raw.lowercase())
        return requireNotNull(value)
    }

    private fun JsonNode.optionalUuid(field: String): UUID? =
        optionalText(field)?.let { raw ->
            val value = runCatching { UUID.fromString(raw) }.getOrNull()
            requireContract(value != null && value.toString() == raw.lowercase())
            value
        }

    private fun JsonNode.requiredInstant(field: String): Instant =
        requiredText(field).toInstant()

    private fun JsonNode.optionalInstant(field: String): Instant? =
        optionalText(field)?.toInstant()

    private fun JsonNode.optionalText(field: String): String? {
        val value = get(field)
        requireContract(value != null)
        if (value.isNull) {
            return null
        }
        requireContract(value.isString)
        val text = value.stringValue()
        requireContract(text.isNotBlank() && text.length <= MAX_TEXT_LENGTH && text.none(Char::isISOControl))
        return text
    }

    private fun JsonNode.requiredLong(field: String): Long {
        val value = get(field)
        requireContract(value != null && value.isIntegralNumber && value.canConvertToLong())
        return value.longValue()
    }

    private fun JsonNode.requiredNonNegativeLong(field: String): Long =
        requiredLong(field).also { requireContract(it >= 0) }

    private fun JsonNode.requiredNonNegativeInt(field: String): Int {
        val value = requiredLong(field)
        requireContract(value in 0..Int.MAX_VALUE.toLong())
        return value.toInt()
    }

    private fun JsonNode.validateActorFingerprint() {
        requireContract(ACTOR_FINGERPRINT.matches(requiredText("actorFingerprint")))
    }

    private fun JsonNode.validateExternalReference() {
        val reference = optionalText("externalReference") ?: return
        requireContract(reference.length in 8..128 && reference.all { it.code in 0x21..0x7e })
    }

    private fun String.toInstant(): Instant =
        try {
            Instant.parse(this)
        } catch (_: DateTimeParseException) {
            throw OutboxContractException()
        }

    private fun String.toLoanStatus(): LoanStatus = when (this) {
        "REQUESTED" -> LoanStatus.LOAN_STATUS_REQUESTED
        "ACTIVE" -> LoanStatus.LOAN_STATUS_ACTIVE
        "RETURNED" -> LoanStatus.LOAN_STATUS_RETURNED
        "REJECTED" -> LoanStatus.LOAN_STATUS_REJECTED
        "CANCELLED" -> LoanStatus.LOAN_STATUS_CANCELLED
        else -> throw OutboxContractException()
    }

    private fun String.toFineStatus(): FineStatus = when (this) {
        "OPEN" -> FineStatus.FINE_STATUS_OPEN
        "SETTLED" -> FineStatus.FINE_STATUS_SETTLED
        "PAID" -> FineStatus.FINE_STATUS_PAID
        "VOID" -> FineStatus.FINE_STATUS_VOID
        else -> throw OutboxContractException()
    }

    private fun String.toCopyStatus(): CopyStatus = when (this) {
        "AVAILABLE" -> CopyStatus.COPY_STATUS_AVAILABLE
        "ON_LOAN" -> CopyStatus.COPY_STATUS_ON_LOAN
        "RESERVED" -> CopyStatus.COPY_STATUS_RESERVED
        "LOST" -> CopyStatus.COPY_STATUS_LOST
        "DAMAGED" -> CopyStatus.COPY_STATUS_DAMAGED
        "WITHDRAWN" -> CopyStatus.COPY_STATUS_WITHDRAWN
        else -> throw OutboxContractException()
    }

    private fun String.toFineLedgerEntryType(): FineLedgerEntryType = when (this) {
        "ASSESSMENT" -> FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_ASSESSMENT
        "PAYMENT" -> FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_PAYMENT
        "ADJUSTMENT" -> FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_ADJUSTMENT
        else -> throw OutboxContractException()
    }

    private fun Instant.toTimestamp(): Timestamp =
        try {
            Timestamp.newBuilder()
                .setSeconds(epochSecond)
                .setNanos(nano)
                .build()
        } catch (_: DateTimeException) {
            throw OutboxContractException()
        }

    private fun requireContract(condition: Boolean) {
        if (!condition) {
            throw OutboxContractException()
        }
    }

    private companion object {
        const val CONTRACT_VERSION = 1
        const val LOAN_AGGREGATE = "loan"
        const val FINE_AGGREGATE = "fine"
        const val COPY_AGGREGATE = "copy"
        const val MAX_TEXT_LENGTH = 512
        val TRACE_ID = Regex("[0-9a-f]{32}")
        val ACTOR_FINGERPRINT = Regex("[0-9a-f]{64}")
        val CURRENCY = Regex("[A-Z]{3}")
        val BARCODE = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{2,63}")
        val EVENT_TYPES = mapOf(
            "circulation.loan.requested" to LOAN_AGGREGATE,
            "circulation.loan.approved" to LOAN_AGGREGATE,
            "circulation.loan.rejected" to LOAN_AGGREGATE,
            "circulation.loan.cancelled" to LOAN_AGGREGATE,
            "circulation.loan.renewed" to LOAN_AGGREGATE,
            "circulation.loan.returned" to LOAN_AGGREGATE,
            "circulation.fine.assessed" to FINE_AGGREGATE,
            "circulation.fine.payment-recorded" to FINE_AGGREGATE,
            "circulation.fine.adjusted" to FINE_AGGREGATE,
            "circulation.copy.registered" to COPY_AGGREGATE,
            "circulation.copy.condition-changed" to COPY_AGGREGATE,
            "circulation.copy.relocated" to COPY_AGGREGATE,
        )
        val LOAN_EVENT_STATUSES = mapOf(
            "circulation.loan.requested" to setOf(LoanStatus.LOAN_STATUS_REQUESTED),
            "circulation.loan.approved" to setOf(LoanStatus.LOAN_STATUS_ACTIVE),
            "circulation.loan.rejected" to setOf(LoanStatus.LOAN_STATUS_REJECTED),
            "circulation.loan.cancelled" to setOf(LoanStatus.LOAN_STATUS_CANCELLED),
            "circulation.loan.renewed" to setOf(LoanStatus.LOAN_STATUS_ACTIVE),
            "circulation.loan.returned" to setOf(LoanStatus.LOAN_STATUS_RETURNED),
        )
        val FINE_EVENT_ENTRY_TYPES = mapOf(
            "circulation.fine.assessed" to FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_ASSESSMENT,
            "circulation.fine.payment-recorded" to FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_PAYMENT,
            "circulation.fine.adjusted" to FineLedgerEntryType.FINE_LEDGER_ENTRY_TYPE_ADJUSTMENT,
        )
        val COPY_EVENT_STATUSES = mapOf(
            "circulation.copy.registered" to setOf(CopyStatus.COPY_STATUS_AVAILABLE),
            "circulation.copy.condition-changed" to setOf(
                CopyStatus.COPY_STATUS_AVAILABLE,
                CopyStatus.COPY_STATUS_LOST,
                CopyStatus.COPY_STATUS_DAMAGED,
                CopyStatus.COPY_STATUS_WITHDRAWN,
            ),
            "circulation.copy.relocated" to setOf(CopyStatus.COPY_STATUS_AVAILABLE),
        )
        val LOAN_PAYLOAD_FIELDS = setOf(
            "loanId",
            "memberId",
            "editionId",
            "copyId",
            "status",
            "requestedAt",
            "checkedOutAt",
            "dueAt",
            "returnedAt",
            "renewalCount",
            "loanVersion",
            "actorFingerprint",
        )
        val FINE_PAYLOAD_FIELDS = setOf(
            "fineId",
            "loanId",
            "memberId",
            "currency",
            "balanceMinor",
            "status",
            "fineVersion",
            "ledgerEntryId",
            "ledgerEntryType",
            "ledgerDeltaMinor",
            "actorFingerprint",
            "externalReference",
            "occurredAt",
        )
        val COPY_PAYLOAD_FIELDS = setOf(
            "copyId",
            "editionId",
            "branchId",
            "barcode",
            "status",
            "shelfLocation",
            "copyVersion",
            "actorFingerprint",
            "reason",
        )
    }
}

class OutboxContractException : RuntimeException("Outbox event does not match the v1 contract")

class OutboxPayloadTooLargeException : RuntimeException("Outbox event exceeds the configured limit")

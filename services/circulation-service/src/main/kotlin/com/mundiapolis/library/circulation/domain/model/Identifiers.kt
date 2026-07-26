package com.mundiapolis.library.circulation.domain.model

import java.util.UUID

@JvmInline
value class LoanId(val value: UUID)

@JvmInline
value class CopyId(val value: UUID)

@JvmInline
value class EditionId(val value: UUID)

@JvmInline
value class MemberId(val value: UUID)

@JvmInline
value class FineId(val value: UUID)

@JvmInline
value class FineLedgerEntryId(val value: UUID)

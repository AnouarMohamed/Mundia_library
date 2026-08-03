package com.mundiapolis.library.circulation.domain.model

enum class CopyStatus {
    AVAILABLE,
    ON_LOAN,
    RESERVED,
    LOST,
    DAMAGED,
    WITHDRAWN,
}

@JvmInline
value class CopyBarcode private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{2,63}")

        fun parse(raw: String): CopyBarcode {
            require(PATTERN.matches(raw)) {
                "Copy barcode must contain 3 to 64 safe barcode characters"
            }
            return CopyBarcode(raw)
        }
    }
}

@JvmInline
value class ShelfLocation private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ShelfLocation {
            require(
                raw.length in 1..128 &&
                    raw == raw.trim() &&
                    raw.none(Char::isISOControl),
            ) { "Shelf location must be trimmed, contain 1 to 128 characters, and have no controls" }
            return ShelfLocation(raw)
        }
    }
}

@JvmInline
value class InventoryReason private constructor(val value: String) {
    companion object {
        fun parse(raw: String): InventoryReason {
            require(
                raw.length in 1..500 &&
                    raw == raw.trim() &&
                    raw.isNotBlank() &&
                    raw.none(Char::isISOControl),
            ) { "Inventory reason must be trimmed, contain 1 to 500 characters, and have no controls" }
            return InventoryReason(raw)
        }
    }
}

data class Copy private constructor(
    val id: CopyId,
    val editionId: EditionId,
    val branchId: BranchId,
    val barcode: CopyBarcode,
    val status: CopyStatus,
    val shelfLocation: ShelfLocation?,
    val version: Long,
) {
    init {
        require(version >= 0) { "Copy version cannot be negative" }
    }

    fun changeCondition(target: CopyStatus): Copy {
        require(target in MANAGED_CONDITIONS) {
            "ON_LOAN and RESERVED are controlled by circulation workflows"
        }
        require(target != status) { "Copy already has the requested condition" }
        require(target in CONDITION_TRANSITIONS.getValue(status)) {
            "Copy condition transition from $status to $target is not allowed"
        }

        return copy(
            status = target,
            shelfLocation = if (target == CopyStatus.AVAILABLE) shelfLocation else null,
            version = version + 1,
        )
    }

    fun relocate(branchId: BranchId, shelfLocation: ShelfLocation): Copy {
        require(status == CopyStatus.AVAILABLE) {
            "Only available copies can be relocated"
        }
        require(this.branchId != branchId || this.shelfLocation != shelfLocation) {
            "Copy already has the requested location"
        }
        return copy(
            branchId = branchId,
            shelfLocation = shelfLocation,
            version = version + 1,
        )
    }

    companion object {
        private val MANAGED_CONDITIONS = setOf(
            CopyStatus.AVAILABLE,
            CopyStatus.LOST,
            CopyStatus.DAMAGED,
            CopyStatus.WITHDRAWN,
        )
        private val CONDITION_TRANSITIONS = mapOf(
            CopyStatus.AVAILABLE to setOf(
                CopyStatus.LOST,
                CopyStatus.DAMAGED,
                CopyStatus.WITHDRAWN,
            ),
            CopyStatus.LOST to setOf(CopyStatus.AVAILABLE, CopyStatus.WITHDRAWN),
            CopyStatus.DAMAGED to setOf(CopyStatus.AVAILABLE, CopyStatus.WITHDRAWN),
            CopyStatus.WITHDRAWN to emptySet(),
            CopyStatus.ON_LOAN to emptySet(),
            CopyStatus.RESERVED to emptySet(),
        )

        fun register(
            id: CopyId,
            editionId: EditionId,
            branchId: BranchId,
            barcode: CopyBarcode,
            shelfLocation: ShelfLocation?,
        ): Copy = Copy(
            id = id,
            editionId = editionId,
            branchId = branchId,
            barcode = barcode,
            status = CopyStatus.AVAILABLE,
            shelfLocation = shelfLocation,
            version = 0,
        )

        fun restore(
            id: CopyId,
            editionId: EditionId,
            branchId: BranchId,
            barcode: CopyBarcode,
            status: CopyStatus,
            shelfLocation: ShelfLocation?,
            version: Long,
        ): Copy = Copy(
            id = id,
            editionId = editionId,
            branchId = branchId,
            barcode = barcode,
            status = status,
            shelfLocation = shelfLocation,
            version = version,
        )
    }
}

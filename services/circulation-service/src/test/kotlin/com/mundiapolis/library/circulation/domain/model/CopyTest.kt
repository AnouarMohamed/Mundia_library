package com.mundiapolis.library.circulation.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CopyTest {
    @Test
    fun `condition changes are explicit versioned and protect workflow states`() {
        val available = availableCopy()
        val damaged = available.changeCondition(CopyStatus.DAMAGED)
        val repaired = damaged.changeCondition(CopyStatus.AVAILABLE)
        val withdrawn = repaired.changeCondition(CopyStatus.WITHDRAWN)

        assertThat(damaged.status).isEqualTo(CopyStatus.DAMAGED)
        assertThat(damaged.shelfLocation).isNull()
        assertThat(repaired.status).isEqualTo(CopyStatus.AVAILABLE)
        assertThat(withdrawn.status).isEqualTo(CopyStatus.WITHDRAWN)
        assertThat(withdrawn.version).isEqualTo(3)
        assertThatThrownBy { withdrawn.changeCondition(CopyStatus.AVAILABLE) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { available.changeCondition(CopyStatus.ON_LOAN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only available copies can be relocated`() {
        val available = availableCopy()
        val destination = BranchId(UUID.randomUUID())
        val relocated = available.relocate(destination, ShelfLocation.parse("B-12"))

        assertThat(relocated.branchId).isEqualTo(destination)
        assertThat(relocated.shelfLocation?.value).isEqualTo("B-12")
        assertThat(relocated.version).isOne()
        assertThatThrownBy {
            relocated.changeCondition(CopyStatus.LOST)
                .relocate(BranchId(UUID.randomUUID()), ShelfLocation.parse("L-01"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `inventory text values reject ambiguous or unsafe representations`() {
        assertThatThrownBy { CopyBarcode.parse("bad barcode") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ShelfLocation.parse(" shelf ") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InventoryReason.parse("line\nbreak") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun availableCopy(): Copy = Copy.register(
        id = CopyId(UUID.randomUUID()),
        editionId = EditionId(UUID.randomUUID()),
        branchId = BranchId(UUID.randomUUID()),
        barcode = CopyBarcode.parse("COPY-${UUID.randomUUID()}"),
        shelfLocation = ShelfLocation.parse("A-01"),
    )
}

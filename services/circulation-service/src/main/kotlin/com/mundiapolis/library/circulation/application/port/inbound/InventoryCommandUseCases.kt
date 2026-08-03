package com.mundiapolis.library.circulation.application.port.inbound

import com.mundiapolis.library.circulation.application.model.CommandPrincipal
import com.mundiapolis.library.circulation.application.model.IdempotencyKey
import com.mundiapolis.library.circulation.application.model.InventoryCommandExecution
import com.mundiapolis.library.circulation.domain.model.BranchId
import com.mundiapolis.library.circulation.domain.model.CopyBarcode
import com.mundiapolis.library.circulation.domain.model.CopyId
import com.mundiapolis.library.circulation.domain.model.CopyStatus
import com.mundiapolis.library.circulation.domain.model.EditionId
import com.mundiapolis.library.circulation.domain.model.InventoryReason
import com.mundiapolis.library.circulation.domain.model.ShelfLocation

data class RegisterCopyCommand(
    val copyId: CopyId,
    val editionId: EditionId,
    val branchId: BranchId,
    val barcode: CopyBarcode,
    val shelfLocation: ShelfLocation?,
    val reason: InventoryReason,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class ChangeCopyConditionCommand(
    val copyId: CopyId,
    val target: CopyStatus,
    val reason: InventoryReason,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

data class RelocateCopyCommand(
    val copyId: CopyId,
    val branchId: BranchId,
    val shelfLocation: ShelfLocation,
    val reason: InventoryReason,
    val idempotencyKey: IdempotencyKey,
    val principal: CommandPrincipal,
)

fun interface RegisterCopyUseCase {
    fun register(command: RegisterCopyCommand): InventoryCommandExecution
}

fun interface ChangeCopyConditionUseCase {
    fun changeCondition(command: ChangeCopyConditionCommand): InventoryCommandExecution
}

fun interface RelocateCopyUseCase {
    fun relocate(command: RelocateCopyCommand): InventoryCommandExecution
}

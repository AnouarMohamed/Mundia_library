package com.mundiapolis.library.migration.eligibility

import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

class EvidenceWriter(private val mapper: ObjectMapper) {
    fun write(destination: Path, evidence: OperatorEvidence) {
        val absolute = destination.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw OperatorValidationException("Evidence path must have a parent directory")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw OperatorValidationException("Evidence parent must be an existing non-symlink directory")
        }
        validateDirectoryPermissions(parent)
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw OperatorValidationException("Evidence file already exists")
        }
        val temporary = Files.createTempFile(
            parent,
            ".eligibility-bootstrap-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS),
        )
        try {
            val payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                var buffer = ByteBuffer.wrap(payload)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.createLink(absolute, temporary)
            Files.delete(temporary)
        } catch (failure: Exception) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    private fun validateDirectoryPermissions(directory: Path) {
        val permissions = runCatching { Files.getPosixFilePermissions(directory) }.getOrNull() ?: return
        if (permissions.any { it in FORBIDDEN_DIRECTORY_PERMISSIONS }) {
            throw OperatorValidationException("Evidence directory must not grant group or other access")
        }
    }

    private companion object {
        val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val FORBIDDEN_DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}

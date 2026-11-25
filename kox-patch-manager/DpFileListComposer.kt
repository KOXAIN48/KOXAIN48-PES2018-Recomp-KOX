package com.koxain.patchmanager

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * First-stage PES 2018 DpFileList.bin composer.
 *
 * Input order is the user's final priority order (highest first). The PES 2018
 * record format is 48 bytes: BE magic, descending priority, constants 100/101,
 * and a 32-byte NUL-padded ISO-8859-1 CPK filename.
 *
 * This class is deliberately independent of Android so it can be unit-tested
 * before it is wired into Patch Manager's UI and multi-source installer.
 */
object DpFileListComposer {
    const val RECORD_SIZE = 48
    const val MAGIC = 0x00000262
    private const val FIELD_0 = 100
    private const val FIELD_1 = 101
    private const val FIRST_PRIORITY = 100

    data class Result(val bytes: ByteArray, val names: List<String>)

    fun compose(highestPriorityFirst: List<String>): Result {
        require(highestPriorityFirst.isNotEmpty()) { "At least one CPK is required." }
        val normalized = highestPriorityFirst.map { raw ->
            val name = raw.trim()
            require(name.isNotEmpty()) { "CPK name cannot be empty." }
            require(name.endsWith(".cpk", ignoreCase = true)) { "Not a CPK filename: $name" }
            require(name.none { it == '/' || it == '\\' || it == '\u0000' }) {
                "Unsafe CPK filename: $name"
            }
            val encoded = name.toByteArray(Charsets.ISO_8859_1)
            require(encoded.size in 1..32) { "CPK filename exceeds the 32-byte field: $name" }
            require(encoded.all { (it.toInt() and 0xFF) in 0x20..0xFF }) {
                "CPK filename contains unsupported control characters: $name"
            }
            name
        }
        val duplicates = normalized.groupBy { it.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate CPK filename(s): ${duplicates.joinToString()}"
        }

        val out = ByteArray((normalized.size + 1) * RECORD_SIZE) // trailing zero sentinel
        normalized.forEachIndexed { index, name ->
            val offset = index * RECORD_SIZE
            putBe32(out, offset, MAGIC)
            putBe32(out, offset + 4, FIRST_PRIORITY - index)
            putBe32(out, offset + 8, FIELD_0)
            putBe32(out, offset + 12, FIELD_1)
            name.toByteArray(Charsets.ISO_8859_1).copyInto(out, offset + 16)
        }
        validate(out, normalized)
        return Result(out, normalized)
    }

    /** Validates the generated structure and strict descending priority. */
    fun validate(bytes: ByteArray, expectedNames: List<String>? = null) {
        require(bytes.size >= RECORD_SIZE && bytes.size % RECORD_SIZE == 0) {
            "DpFileList size must contain whole 48-byte records."
        }
        val parsed = ArrayList<String>()
        var previousPriority = Int.MAX_VALUE
        var offset = 0
        while (offset + RECORD_SIZE <= bytes.size) {
            val magic = getBe32(bytes, offset)
            val priority = getBe32(bytes, offset + 4)
            val field0 = getBe32(bytes, offset + 8)
            val field1 = getBe32(bytes, offset + 12)
            if (magic == 0 && priority == 0 && field0 == 0 && field1 == 0) break
            require(magic == MAGIC) { "Invalid DpFileList magic at record ${parsed.size}." }
            require(field0 == FIELD_0 && field1 == FIELD_1) {
                "Invalid DpFileList constants at record ${parsed.size}."
            }
            require(priority < previousPriority) {
                "Priority values must decrease strictly from top to bottom."
            }
            previousPriority = priority
            val field = bytes.copyOfRange(offset + 16, offset + RECORD_SIZE)
            val name = String(field, Charsets.ISO_8859_1).substringBefore('\u0000')
            require(name.isNotBlank() && name.endsWith(".cpk", ignoreCase = true)) {
                "Invalid CPK name at record ${parsed.size}."
            }
            parsed += name
            offset += RECORD_SIZE
        }
        require(parsed.isNotEmpty()) { "DpFileList contains no CPK entries." }
        require(parsed.map { it.lowercase(Locale.ROOT) }.distinct().size == parsed.size) {
            "DpFileList contains duplicate CPK names."
        }
        if (expectedNames != null) {
            require(parsed == expectedNames) { "Generated DpFileList order does not match the selected priority." }
        }
    }

    /**
     * Writes a verified temporary file, preserves the previous output as backup,
     * then swaps the new file into place. Existing output is never truncated
     * before the backup and generated file have both been verified.
     */
    fun writeWithBackup(output: File, backup: File, highestPriorityFirst: List<String>): Result {
        val result = compose(highestPriorityFirst)
        val parent = output.absoluteFile.parentFile
            ?: throw IllegalArgumentException("Output must have a parent directory.")
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create output directory." }
        val temp = File(parent, output.name + ".kox-tmp")
        val backupTemp = File(backup.absoluteFile.parentFile ?: parent, backup.name + ".kox-tmp")
        try {
            FileOutputStream(temp).use { it.write(result.bytes); it.fd.sync() }
            validate(temp.readBytes(), result.names)
            if (output.exists()) {
                val backupParent = backup.absoluteFile.parentFile ?: parent
                require(backupParent.isDirectory || backupParent.mkdirs()) {
                    "Cannot create backup directory."
                }
                Files.copy(output.toPath(), backupTemp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                require(backupTemp.length() == output.length()) { "Backup verification failed." }
                move(backupTemp, backup)
            }
            move(temp, output)
            validate(output.readBytes(), result.names)
            return result
        } finally {
            temp.delete()
            backupTemp.delete()
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun getBe32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

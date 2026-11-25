package com.koxain.patchmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DpFileListComposerTest {
    @Test fun writes48ByteRecordsAndZeroSentinel() {
        val result = DpFileListComposer.compose(listOf("stadium.cpk", "turf.cpk"))
        assertEquals(3 * 48, result.bytes.size)
        assertEquals(0x262, readInt(result.bytes, 0))
        assertEquals(100, readInt(result.bytes, 4))
        assertEquals(99, readInt(result.bytes, 48 + 4))
        assertEquals(0, readInt(result.bytes, 96))
        DpFileListComposer.validate(result.bytes, listOf("stadium.cpk", "turf.cpk"))
    }

    @Test fun rejectsDuplicateNamesCaseInsensitively() {
        val error = runCatching {
            DpFileListComposer.compose(listOf("Stadium.cpk", "stadium.CPK"))
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Duplicate"))
    }

    @Test fun rejectsUnsafeOrOversizedNames() {
        assertFalse(runCatching { DpFileListComposer.compose(listOf("../bad.cpk")) }.isSuccess)
        assertFalse(runCatching { DpFileListComposer.compose(listOf("this-cpk-name-is-far-too-long-to-fit.cpk")) }.isSuccess)
    }

    @Test fun preservesOldOutputBeforeReplacing() {
        val dir = createTempDir(prefix = "dpfilelist-test")
        try {
            val output = File(dir, "DpFileList.bin")
            val backup = File(dir, "backup/DpFileList.bin.bak")
            val old = byteArrayOf(1, 2, 3, 4)
            output.writeBytes(old)
            DpFileListComposer.writeWithBackup(output, backup, listOf("new.cpk"))
            assertTrue(backup.isFile)
            assertTrue(backup.readBytes().contentEquals(old))
            DpFileListComposer.validate(output.readBytes(), listOf("new.cpk"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

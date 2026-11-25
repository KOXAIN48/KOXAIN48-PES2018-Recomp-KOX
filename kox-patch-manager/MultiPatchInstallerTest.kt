package com.koxain.patchmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MultiPatchInstallerTest {
    @Test fun mergesSourcesAndKeepsPreviousActiveTreeAsBackup() {
        val root = createTempDir(prefix = "multi-patch")
        try {
            val first = File(root, "source-stadium").apply { mkdirs() }
            val second = File(root, "source-turf").apply { mkdirs() }
            File(first, "stadium.cpk").writeBytes(cpk())
            File(second, "turf.cpk").writeBytes(cpk())
            val active = File(root, "dlc/SYSTEM00000000").apply { mkdirs() }
            File(active, "old.cpk").writeBytes(cpk())
            File(active, "DpFileList.bin").writeBytes(DpFileListComposer.compose(listOf("old.cpk")).bytes)

            val report = MultiPatchInstaller(active, File(root, "patch-backups")).install(
                listOf(
                    MultiPatchInstaller.Source("stadium", "Stadium", first),
                    MultiPatchInstaller.Source("turf", "Turf", second),
                ),
            )
            assertEquals(listOf("stadium.cpk", "turf.cpk"), report.cpkNames)
            assertTrue(File(active, "stadium.cpk").isFile)
            assertTrue(File(active, "turf.cpk").isFile)
            assertFalse(File(active, "old.cpk").exists())
            assertTrue(report.backup?.let { File(it, "old.cpk").isFile } == true)
            DpFileListComposer.validate(File(active, "DpFileList.bin").readBytes(), report.cpkNames)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsDuplicateCpkNamesByDefaultWithoutChangingActive() {
        val root = createTempDir(prefix = "multi-conflict")
        try {
            val first = File(root, "a").apply { mkdirs() }
            val second = File(root, "b").apply { mkdirs() }
            File(first, "common.cpk").writeBytes(cpk())
            File(second, "COMMON.CPK").writeBytes(cpk())
            val active = File(root, "active").apply { mkdirs() }
            File(active, "keep.cpk").writeBytes(cpk())

            val error = runCatching {
                MultiPatchInstaller(active, File(root, "backups")).install(
                    listOf(
                        MultiPatchInstaller.Source("a", "A", first),
                        MultiPatchInstaller.Source("b", "B", second),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("conflicts"))
            assertTrue(File(active, "keep.cpk").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun explicitPriorityKeepsFirstDuplicate() {
        val root = createTempDir(prefix = "multi-priority")
        try {
            val first = File(root, "higher").apply { mkdirs() }
            val second = File(root, "lower").apply { mkdirs() }
            File(first, "ball.cpk").writeBytes(cpk(0xFF))
            File(second, "BALL.CPK").writeBytes(cpk(0x00))
            val active = File(root, "active")
            val report = MultiPatchInstaller(active, File(root, "backups")).install(
                listOf(
                    MultiPatchInstaller.Source("high", "Higher", first),
                    MultiPatchInstaller.Source("low", "Lower", second),
                ),
                MultiPatchInstaller.ConflictPolicy.KEEP_HIGHEST_PRIORITY,
            )
            assertEquals(listOf("ball.cpk"), report.cpkNames)
            assertEquals("Higher", report.conflicts.single().keptSource)
            assertTrue(report.conflicts.single().ignoredSources.contains("Lower"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun cpk(variant: Int = 0xFF) =
        byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), ' '.code.toByte(), variant.toByte()) +
            ByteArray(32)
}

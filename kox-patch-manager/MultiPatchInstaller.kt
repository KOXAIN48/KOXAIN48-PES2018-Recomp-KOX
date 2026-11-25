package com.koxain.patchmanager

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Offline multi-source CPK merge engine. The caller supplies already extracted
 * patch folders and the source order selected by the user (highest priority
 * first). This engine does not guess category priority.
 *
 * It builds a complete staging tree, verifies every copied CPK and generated
 * DpFileList, then swaps the active patch directory. The previous active tree
 * is retained in a timestamped backup directory.
 */
class MultiPatchInstaller(
    private val activePatchDirectory: File,
    private val backupDirectory: File,
) {
    enum class ConflictPolicy {
        /** Stop without changing the active patch when two sources contain the same CPK name. */
        REJECT,
        /** Keep the first occurrence in the user's highest-priority-first source order. */
        KEEP_HIGHEST_PRIORITY,
    }

    data class Source(
        val id: String,
        val label: String,
        val directory: File,
        /** Relative paths selected by the user; null means include every CPK. */
        val selectedRelativePaths: Set<String>? = null,
    )
    data class Conflict(val fileName: String, val keptSource: String, val ignoredSources: List<String>)
    data class InstallReport(
        val cpkNames: List<String>,
        val sourceCount: Int,
        val conflicts: List<Conflict>,
        val backup: File?,
    )

    private data class Candidate(val source: Source, val file: File)

    fun install(
        sourcesHighestPriorityFirst: List<Source>,
        conflictPolicy: ConflictPolicy = ConflictPolicy.REJECT,
    ): InstallReport {
        require(sourcesHighestPriorityFirst.isNotEmpty()) { "Select at least one patch source." }
        require(sourcesHighestPriorityFirst.map { it.id }.distinct().size == sourcesHighestPriorityFirst.size) {
            "Patch source IDs must be unique."
        }
        sourcesHighestPriorityFirst.forEach { source ->
            require(source.id.isNotBlank()) { "Patch source ID cannot be empty." }
            require(source.directory.isDirectory) { "Source folder is missing: ${source.label}" }
        }

        val byName = LinkedHashMap<String, MutableList<Candidate>>()
        sourcesHighestPriorityFirst.forEach { source ->
            val allFiles = source.directory.walkTopDown()
                .filter { it.isFile && it.extension.equals("cpk", ignoreCase = true) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .toList()
            require(allFiles.isNotEmpty()) { "No CPK files found in source: ${source.label}" }
            val files = source.selectedRelativePaths?.let { selected ->
                allFiles.filter { it.relativeTo(source.directory).invariantSeparatorsPath in selected }
            } ?: allFiles
            files.forEach { file ->
                validateCpk(file)
                byName.getOrPut(file.name.lowercase(Locale.ROOT)) { mutableListOf() }
                    .add(Candidate(source, file))
            }
        }
        require(byName.isNotEmpty()) { "Select at least one CPK file before generating the combined patch." }

        val conflicts = byName.values.filter { it.size > 1 }.map { candidates ->
            Conflict(
                fileName = candidates.first().file.name,
                keptSource = candidates.first().source.label,
                ignoredSources = candidates.drop(1).map { it.source.label },
            )
        }
        if (conflicts.isNotEmpty() && conflictPolicy == ConflictPolicy.REJECT) {
            throw IllegalArgumentException(
                "CPK name conflicts: " + conflicts.joinToString { it.fileName } +
                    ". Resolve conflicts or choose KEEP_HIGHEST_PRIORITY.",
            )
        }

        val selected = byName.values.map { it.first() }
        val stageParent = activePatchDirectory.absoluteFile.parentFile
            ?: throw IOException("Active patch directory has no parent.")
        if (!stageParent.isDirectory && !stageParent.mkdirs()) {
            throw IOException("Cannot create patch directory: $stageParent")
        }
        val stage = File(stageParent, activePatchDirectory.name + ".kox-stage")
        val displaced = File(stageParent, activePatchDirectory.name + ".kox-displaced")
        val backup = File(backupDirectory, "multi-${System.currentTimeMillis()}")
        stage.deleteRecursively()
        displaced.deleteRecursively()
        if (!stage.mkdirs()) throw IOException("Cannot create staging directory: $stage")

        var activeMoved = false
        var newActiveMoved = false
        try {
            selected.forEach { candidate ->
                val destination = File(stage, candidate.file.name)
                copyAndVerify(candidate.file, destination)
            }
            val names = selected.map { it.file.name }
            val dp = DpFileListComposer.compose(names)
            val dpFile = File(stage, "DpFileList.bin")
            FileOutputStream(dpFile).use { out ->
                out.write(dp.bytes)
                out.fd.sync()
            }
            DpFileListComposer.validate(dpFile.readBytes(), names)
            val stagedCpks = stage.listFiles()?.filter {
                it.isFile && it.extension.equals("cpk", ignoreCase = true)
            }.orEmpty()
            require(stagedCpks.size == names.size) { "Staging CPK count does not match the merge plan." }

            if (activePatchDirectory.exists()) {
                val backupParent = backup.parentFile
                    ?: throw IOException("Backup directory has no parent.")
                if (!backupParent.isDirectory && !backupParent.mkdirs()) {
                    throw IOException("Cannot create backup directory: $backupParent")
                }
                if (!activePatchDirectory.renameTo(displaced)) {
                    throw IOException("Cannot move the active patch aside.")
                }
                activeMoved = true
            }
            if (!stage.renameTo(activePatchDirectory)) {
                throw IOException("Cannot activate the generated patch set.")
            }
            newActiveMoved = true

            if (activeMoved) {
                if (!displaced.renameTo(backup)) {
                    // Preserve the old active tree in the displaced path; do not delete it.
                    throw IOException("New patch is active, but the backup remains at ${displaced.path}.")
                }
            }
            return InstallReport(names, sourcesHighestPriorityFirst.size, conflicts, backup.takeIf { activeMoved })
        } catch (t: Throwable) {
            if (newActiveMoved) activePatchDirectory.deleteRecursively()
            if (activeMoved && displaced.exists()) {
                if (!displaced.renameTo(activePatchDirectory)) {
                    throw IOException(
                        "Install failed (${t.message}) and automatic rollback failed; previous patch remains at ${displaced.path}.",
                        t,
                    )
                }
            }
            throw t
        } finally {
            stage.deleteRecursively()
            // Never delete displaced here: it may be the only surviving copy after a failed rollback.
        }
    }

    private fun validateCpk(file: File) {
        require(file.length() >= 5L) { "CPK is truncated: ${file.name}" }
        FileInputStream(file).use { input ->
            val header = ByteArray(5)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            require(read == 5 && String(header, 0, 4, Charsets.ISO_8859_1) == "CPK ") {
                "Invalid CPK header: ${file.name}"
            }
            val variant = header[4].toInt() and 0xFF
            require(variant == 0xFF || variant == 0x00) {
                "Unsupported CPK variant 0x${variant.toString(16)}: ${file.name}"
            }
        }
    }

    private fun copyAndVerify(source: File, destination: File) {
        val input = BufferedInputStream(FileInputStream(source))
        val output = BufferedOutputStream(FileOutputStream(destination))
        try {
            input.copyTo(output, 1024 * 1024)
            output.flush()
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
        require(destination.length() == source.length()) {
            "Copied CPK size mismatch: ${source.name}"
        }
        validateCpk(destination)
    }
}

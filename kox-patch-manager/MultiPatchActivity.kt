package com.pes2017.recomp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.koxain.patchmanager.MultiPatchInstaller
import com.pes2017.recomp.patch.install.PatchInstaller
import com.pes2017.recomp.patch.install.PatchStateStore
import com.pes2017.recomp.patch.install.InstallSlot
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.concurrent.thread

/** UI for combining several already-extracted Xbox 360 PES 2018 patch folders. */
class MultiPatchActivity : AppCompatActivity() {
    private data class SavedSource(val id: String, val label: String, val directory: File)

    private lateinit var list: LinearLayout
    private lateinit var conflictChoice: CheckBox
    private val sources = mutableListOf<SavedSource>()
    private val sourceRoot by lazy { File(filesDir, "patch-sources") }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSources()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(0xFF0B1220.toInt())
        }
        val title = TextView(this).apply {
            text = "PILIH CPK PATCH"
            textSize = 20f
            setTextColor(0xFF38BDF8.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        page.addView(title)
        page.addView(TextView(this).apply {
            text = "Pilih folder patch hasil ekstrak. Semua file CPK akan ditampilkan agar bisa dipilih satu per satu sebelum instalasi."
            textSize = 13f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })
        val add = actionButton("PILIH FOLDER PATCH") { folderPicker.launch(null) }
        page.addView(add)
        conflictChoice = CheckBox(this).apply {
            text = "Jika nama CPK sama, gunakan sumber dengan prioritas lebih tinggi"
            setTextColor(0xFFE2E8F0.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF38BDF8.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        conflictChoice.visibility = View.GONE
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        page.addView(actionButton("GENERATE & PASANG GABUNGAN") { generate() })
        page.addView(actionButton("KEMBALI") { finish() })
        setContentView(page)
        renderSources()
    }

    private fun importFolder(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.isDirectory) {
            toast("Folder tidak bisa dibaca.")
            return
        }
        val id = UUID.randomUUID().toString()
        val destination = File(sourceRoot, id)
        thread(name = "kox-patch-import") {
            try {
                if (!destination.mkdirs() && !destination.isDirectory) error("Tidak bisa membuat folder sumber.")
                var count = 0
                copyCpks(root, destination) { count++ }
                if (count == 0) error("Folder tidak berisi file CPK.")
                runOnUiThread {
                    // Persist first, then reload from disk so the UI reflects the
                    // exact source set that will survive activity recreation.
                    sources.forEach { it.directory.deleteRecursively() }
                    sources.clear()
                    sources += SavedSource(id, root.name?.takeIf { it.isNotBlank() } ?: "Patch $count CPK", destination)
                    persistSources()
                    sources.clear()
                    loadSources()
                    renderSources()
                    toast("$count CPK ditambahkan.")
                }
            } catch (t: Throwable) {
                destination.deleteRecursively()
                runOnUiThread { toast(t.message ?: "Gagal membaca folder patch.") }
            }
        }
    }

    private fun copyCpks(node: DocumentFile, destination: File, onCopied: () -> Unit) {
        node.listFiles().forEach { child ->
            if (child.isDirectory) {
                val nested = File(destination, safeName(child.name ?: "folder"))
                if (!nested.mkdirs() && !nested.isDirectory) error("Tidak bisa membuat subfolder.")
                copyCpks(child, nested, onCopied)
            } else if (child.isFile && child.name?.endsWith(".cpk", ignoreCase = true) == true) {
                val target = File(destination, safeName(child.name!!))
                contentResolver.openInputStream(child.uri).use { input ->
                    requireNotNull(input) { "Tidak bisa membaca ${child.name}" }
                    FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024); output.fd.sync() }
                }
                onCopied()
            }
        }
    }

    private fun generate() {
        if (sources.isEmpty()) {
            toast("Tambahkan minimal satu folder patch.")
            return
        }
        if (SaveBackup.gameRunning(this)) {
            toast("Tutup game sebelum memasang patch.")
            return
        }
        val gamePath = Prefs(this).gameDirectory
        val game = File(gamePath)
        val root = game.parentFile
        if (gamePath.isBlank() || !File(game, "default.xex").isFile || root == null) {
            toast("Folder game PES 2018 belum dipilih.")
            return
        }
        if (PatchInstaller.pending(root)) {
            toast("Operasi patch lain masih berjalan atau pemulihan belum selesai.")
            return
        }
        val active = File(root, "dlc/SYSTEM00000000")
        val policy = MultiPatchInstaller.ConflictPolicy.REJECT
        val dialog = AlertDialog.Builder(this)
            .setTitle("Generate DpFileList gabungan?")
            .setMessage("Sumber: ${sources.size}\nPrioritas: dari atas ke bawah\nHanya CPK yang dicentang akan dipasang. File patch lama tidak dicadangkan.")
            .setNegativeButton("BATAL", null)
            .setPositiveButton("GENERATE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                runInstall(active, policy)
            }
        }
        dialog.show()
    }

    private fun runInstall(
        active: File,
        policy: MultiPatchInstaller.ConflictPolicy,
    ) {
        val progress = AlertDialog.Builder(this)
            .setTitle("KOX Multi-Patch")
            .setMessage("Memvalidasi CPK, memeriksa konflik, dan membuat file gabungan…")
            .setCancelable(false)
            .create()
        progress.show()
        thread(name = "kox-multi-patch-install") {
            val result = runCatching {
                MultiPatchInstaller(active).install(
                    sources.map { MultiPatchInstaller.Source(it.id, it.label, it.directory, selectedCpkPaths(it)) },
                    policy,
                )
            }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { report ->
                    // The merged set is now a hand-managed pack; discard the
                    // old app provenance so PATCHES verifies the live files.
                    PatchStateStore.clearSlot(active.parentFile.parentFile, InstallSlot.PATCH)
                    val conflicts = if (report.conflicts.isEmpty()) "Tidak ada konflik nama CPK."
                    else "Konflik diselesaikan: ${report.conflicts.joinToString { it.fileName }}."
                    AlertDialog.Builder(this)
                        .setTitle("Patch gabungan berhasil")
                        .setMessage("${report.cpkNames.size} CPK dipasang dari ${report.sourceCount} sumber.\n$conflicts\nTidak ada backup patch yang dibuat.")
                        .setPositiveButton("OK", null)
                        .show()
                }.onFailure { error ->
                    AlertDialog.Builder(this)
                        .setTitle("Generate gagal")
                        .setMessage(error.message ?: error.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun renderSources() {
        list.removeAllViews()
        if (sources.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada folder patch. Tambahkan folder hasil ekstrak yang berisi file CPK."
                setTextColor(0xFFCBD5E1.toInt())
                setPadding(0, dp(14), 0, dp(14))
            })
        }
        sources.forEachIndexed { index, source ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFF172033.toInt())
            }
            card.addView(TextView(this).apply {
                text = "${index + 1}. ${source.label}"
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            val cpkFiles = source.directory.walkTopDown()
                .filter { it.isFile && it.extension.equals("cpk", ignoreCase = true) }
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .toList()
            card.addView(TextView(this).apply {
                text = "${cpkFiles.size} file CPK"
                textSize = 12f
                setTextColor(0xFF38BDF8.toInt())
                setPadding(0, dp(3), 0, dp(6))
            })
            cpkFiles.forEach { cpk ->
                val relativePath = cpk.relativeTo(source.directory).invariantSeparatorsPath
                val selectionKey = "selected:${source.id}:$relativePath"
                val selectedByDefault = getSharedPreferences("kox_multi_patch", MODE_PRIVATE)
                    .getBoolean(selectionKey, true)
                val check = CheckBox(this).apply {
                    text = "${cpk.name}  (${android.text.format.Formatter.formatFileSize(this@MultiPatchActivity, cpk.length())})"
                    textSize = 11f
                    setTextColor(0xFFE2E8F0.toInt())
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFF38BDF8.toInt())
                    isChecked = selectedByDefault
                    setPadding(dp(4), dp(2), 0, dp(2))
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("kox_multi_patch", MODE_PRIVATE)
                            .edit().putBoolean(selectionKey, checked).apply()
                    }
                }
                card.addView(check)
            }
            card.addView(TextView(this).apply {
                text = source.directory.absolutePath
                textSize = 9f
                setTextColor(0xFF94A3B8.toInt())
            })
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(actionButton("GANTI / HAPUS FOLDER") { source.directory.deleteRecursively(); sources.removeAt(index); persistSources(); renderSources() }, weight())
            card.addView(controls)
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun selectedCpkPaths(source: SavedSource): Set<String> {
        val prefs = getSharedPreferences("kox_multi_patch", MODE_PRIVATE)
        return source.directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("cpk", ignoreCase = true) }
            .filter { file ->
                val relativePath = file.relativeTo(source.directory).invariantSeparatorsPath
                prefs.getBoolean("selected:${source.id}:$relativePath", true)
            }
            .map { it.relativeTo(source.directory).invariantSeparatorsPath }
            .toSet()
    }

    private fun loadSources() {
        sourceRoot.mkdirs()
        sources.clear()
        val raw = getSharedPreferences("kox_multi_patch", MODE_PRIVATE).getString("sources", "") ?: ""
        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2) {
                val dir = File(sourceRoot, parts[0])
                if (dir.isDirectory) sources += SavedSource(parts[0], parts[1], dir)
            }
        }
    }

    private fun persistSources() {
        val value = sources.joinToString("\n") { "${it.id}\t${it.label.replace('\n', ' ')}" }
        val saved = getSharedPreferences("kox_multi_patch", MODE_PRIVATE)
            .edit().putString("sources", value).commit()
        check(saved) { "Gagal menyimpan daftar folder patch." }
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
        }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

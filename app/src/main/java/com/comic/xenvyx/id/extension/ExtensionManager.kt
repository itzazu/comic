package com.comic.xenvyx.id.extension

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** App-private browser-style content-script extension store. No Android JS bridge is exposed. */
class ExtensionManager(private val context: Context) {
    data class Extension(val id: String, val name: String, val version: String, val description: String,
        val enabled: Boolean, val format: String, val matches: List<String>, val scripts: List<String>)
    data class ImportResult(val extension: Extension, val warning: String)

    private val root = File(context.filesDir, "extensions").apply { mkdirs() }
    private val maxFile = 8 * 1024 * 1024
    private val maxExpanded = 12 * 1024 * 1024

    fun list(): List<Extension> = root.listFiles()?.filter { it.isDirectory }
        ?.mapNotNull { runCatching { readMeta(it) }.getOrNull() }
        ?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()

    fun setEnabled(id: String, enabled: Boolean) { val d = dir(id); writeMeta(d, readMeta(d).copy(enabled = enabled)) }
    fun remove(id: String) { dir(id).deleteRecursively() }

    fun `import`(uri: Uri): ImportResult {
        val name = displayName(uri).lowercase(Locale.ROOT)
        if (name.endsWith(".apk")) error("APK adalah aplikasi Android, bukan extension browser. APK tidak dapat dipasang di Extension Manager.")
        val bytes = context.contentResolver.openInputStream(uri)?.use { bounded(it, maxFile) } ?: error("File tidak dapat dibaca.")
        return when {
            isApk(bytes) -> error("APK terdeteksi, tetapi APK adalah aplikasi Android, bukan extension browser. APK tidak dapat dipasang di Extension Manager.")
            name.endsWith(".crx") || isCrx(bytes) -> importZip(crxPayload(bytes), "CRX")
            name.endsWith(".js") -> importJs(name, bytes)
            name.endsWith(".zip") || isZip(bytes) -> importZip(bytes, "ZIP")
            else -> error("Format tidak dikenali. Pilih .zip, .js, .crx, atau .apk.")
        }
    }

    fun scriptsFor(url: String): List<String> {
        if (!url.startsWith("https://", true)) return emptyList()
        return list().filter { it.enabled && it.matches.any { p -> urlMatches(p, url) } }.flatMap { ext ->
            val d = dir(ext.id)
            ext.scripts.mapNotNull { p -> File(d, p).takeIf { it.isFile }?.readText() }
        }
    }

    private fun importJs(name: String, bytes: ByteArray): ImportResult {
        require(bytes.isNotEmpty() && bytes.size <= 1024 * 1024) { "File JS kosong atau melebihi 1 MiB." }
        val label = name.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32).ifBlank { "Imported JS" }
        val id = unique("js_${label}")
        val d = File(root, id).apply { mkdirs() }
        File(d, "content.js").writeBytes(bytes)
        val e = Extension(id, label, "1.0.0", "JavaScript yang diimpor langsung", false, "JS",
            listOf("https://komiku.org/*", "https://www.komiku.org/*"), listOf("content.js"))
        writeMeta(d, e)
        return ImportResult(e, "Script diimpor dalam keadaan nonaktif dan cakupannya dibatasi ke Komiku.org.")
    }

    private fun importZip(bytes: ByteArray, format: String): ImportResult {
        require(bytes.size <= maxFile) { "Arsip melebihi 8 MiB." }
        val files = unzip(bytes)
        val manifest = files["manifest.json"] ?: error("manifest.json tidak ditemukan di root ZIP.")
        require(manifest.size <= 128 * 1024) { "manifest.json terlalu besar." }
        val j = JSONObject(manifest.toString(Charsets.UTF_8))
        val mv = j.optInt("manifest_version", 1)
        require(mv in 1..3) { "Manifest browser versi $mv tidak didukung." }
        val name = j.optString("name").trim()
        val version = j.optString("version", "1.0.0").trim()
        require(name.isNotBlank() && name.length <= 80 && version.isNotBlank() && version.length <= 32) { "Nama atau versi extension tidak valid." }
        require(!j.has("background") && !j.has("nativeMessaging") && !j.has("externally_connectable")) {
            "Extension memakai background/service worker atau API native yang tidak didukung."
        }
        val denied = setOf("tabs", "cookies", "webRequest", "webRequestBlocking", "nativeMessaging", "debugger", "history", "downloads")
        val permissions = j.optJSONArray("permissions") ?: JSONArray()
        for (i in 0 until permissions.length()) require(permissions.optString(i) !in denied) { "Permission ${permissions.optString(i)} tidak didukung." }

        val scriptPaths = linkedSetOf<String>()
        val matches = linkedSetOf<String>()
        val contentScripts = j.optJSONArray("content_scripts")
        if (contentScripts != null) {
            for (i in 0 until contentScripts.length()) {
                val item = contentScripts.getJSONObject(i)
                val js = item.optJSONArray("js") ?: JSONArray()
                val urls = item.optJSONArray("matches") ?: JSONArray()
                require(js.length() in 1..10 && urls.length() in 1..30) { "Setiap content_scripts harus punya 1-10 JS dan 1-30 pola URL." }
                for (n in 0 until urls.length()) matches += validMatch(urls.getString(n))
                for (n in 0 until js.length()) {
                    val p = safePath(js.getString(n)); val data = files[p] ?: error("Script $p tidak ditemukan.")
                    require(data.size <= 1024 * 1024) { "Script $p melebihi 1 MiB." }; scriptPaths += p
                }
            }
        } else {
            val p = safePath(j.optString("script")); require(p.isNotBlank()) { "Manifest tidak memiliki content_scripts atau script." }
            val data = files[p] ?: error("Script $p tidak ditemukan.")
            require(data.size <= 1024 * 1024) { "Script melebihi 1 MiB." }; scriptPaths += p
            val urls = j.optJSONArray("matches") ?: JSONArray().put("https://komiku.org/*").put("https://www.komiku.org/*")
            for (n in 0 until urls.length()) matches += validMatch(urls.getString(n))
        }
        require(scriptPaths.isNotEmpty() && matches.isNotEmpty()) { "Extension tidak memiliki script atau cakupan HTTPS." }
        val id = unique(j.optString("id").ifBlank { "${slug(name)}_${version.replace('.', '_')}" })
        val d = File(root, id).apply { mkdirs() }
        try {
            scriptPaths.forEach { p -> File(d, p).apply { parentFile?.mkdirs(); writeBytes(files.getValue(p)) } }
            val ext = Extension(id, name, version, j.optString("description").take(240), false, format, matches.toList(), scriptPaths.toList())
            writeMeta(d, ext)
            return ImportResult(ext, if (format == "CRX") "CRX berhasil dibaca, tetapi tanda tangan penerbit tidak diverifikasi. Extension diimpor nonaktif; aktifkan hanya jika sumber tepercaya." else "Extension diimpor nonaktif. Periksa sumber dan izin situs sebelum mengaktifkannya.")
        } catch (t: Throwable) { d.deleteRecursively(); throw t }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>(); var total = 0; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    require(++count <= 200) { "Arsip berisi lebih dari 200 file." }
                    val path = safePath(e.name); val data = bounded(z, maxExpanded - total)
                    total += data.size; require(total <= maxExpanded) { "Hasil ekstraksi melebihi 12 MiB." }; out[path] = data
                }
                z.closeEntry(); e = z.nextEntry
            }
        }
        return out
    }

    private fun crxPayload(b: ByteArray): ByteArray {
        require(b.size >= 12 && isCrx(b)) { "Header CRX tidak valid." }
        fun u32(i: Int) = (b[i].toLong() and 255) or ((b[i+1].toLong() and 255) shl 8) or ((b[i+2].toLong() and 255) shl 16) or ((b[i+3].toLong() and 255) shl 24)
        val offset = when (u32(4).toInt()) {
            2 -> { require(b.size >= 16); val key = u32(8); val sig = u32(12); require(key in 1..65536 && sig in 1..65536); 16L + key + sig }
            3 -> 12L + u32(8)
            else -> error("Versi CRX tidak didukung.")
        }
        require(offset in 1 until b.size.toLong()) { "Payload ZIP CRX tidak ditemukan." }
        return b.copyOfRange(offset.toInt(), b.size).also { require(isZip(it)) { "Payload CRX bukan ZIP." } }
    }

    private fun validMatch(p: String): String {
        require(p.startsWith("https://") && p.length <= 240 && !p.contains('@') && !p.contains("..")) { "Pola URL harus HTTPS: $p" }
        val host = p.removePrefix("https://").substringBefore('/').removePrefix("*.")
        require(host.isNotBlank() && host.matches(Regex("[A-Za-z0-9.*-]+"))) { "Host pola URL tidak valid: $p" }
        return p
    }
    private fun urlMatches(pattern: String, url: String): Boolean {
        val p = pattern.removePrefix("https://"); val u = url.removePrefix("https://")
        val regex = p.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(u)
    }
    private fun safePath(raw: String): String {
        val p = raw.replace('\\', '/')
        require(p.isNotBlank() && !p.startsWith('/') && !p.split('/').any { it.isBlank() || it == ".." }) { "Path arsip tidak aman: $raw" }
        return p
    }
    private fun readMeta(d: File): Extension {
        val j = JSONObject(File(d, "extension.json").readText())
        fun arr(k: String) = j.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Extension(j.getString("id"), j.getString("name"), j.getString("version"), j.optString("description"),
            j.optBoolean("enabled"), j.optString("format"), arr("matches"), arr("scripts"))
    }
    private fun writeMeta(d: File, e: Extension) {
        File(d, "extension.json").writeText(JSONObject().put("id", e.id).put("name", e.name).put("version", e.version)
            .put("description", e.description).put("enabled", e.enabled).put("format", e.format)
            .put("matches", JSONArray(e.matches)).put("scripts", JSONArray(e.scripts)).toString())
    }
    private fun dir(id: String): File { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))); return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) } }
    private fun displayName(uri: Uri) = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" } ?: "" }.getOrDefault("")
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        require(limit > 0) { "Ukuran file melebihi batas." }; val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var nTotal = 0
        while (true) { val n = input.read(buf); if (n < 0) break; nTotal += n; require(nTotal <= limit) { "File melebihi batas ukuran." }; out.write(buf, 0, n) }
        return out.toByteArray()
    }
    private fun unique(raw: String): String { val base = slug(raw).take(55).ifBlank { "extension" }; var id = base; var i = 2; while (File(root, id).exists()) id = "${base}_${i++}"; return id }
    private fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_").trim('_')
    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte()
    private fun isCrx(b: ByteArray) = b.size >= 4 && String(b, 0, 4, Charsets.US_ASCII) == "Cr24"
    private fun isApk(b: ByteArray) = isZip(b) && runCatching { unzip(b).containsKey("AndroidManifest.xml") }.getOrDefault(false)
}

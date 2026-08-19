package com.xverse.app.core.extensions

import android.content.Context
import android.net.Uri
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 扩展导入管线：CRX/裸 ZIP 解压注册 + 商店链接/扩展 ID 拉取。
 *
 * 格式说明：Chrome 商店分发的是 .crx（Cr24 头 + 签名 + ZIP 包），
 * 第三方常提供裸 .zip。两者都兼容：Cr24 开头按 CRX 跳过签名头，否则整文件按 ZIP。
 *
 * 安全：
 *  - 解压条目名拒绝 `..` / 绝对路径 / 首字符 `/`（防路径穿越）
 *  - 条目数 ≤ 2000、解压总量 ≤ 50MB（zip 炸弹防护）
 */
class ExtensionImporter(
    private val context: Context,
    private val repo: ExtensionRepo,
) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val extRoot: File
        get() = File(context.filesDir, "extensions").apply { mkdirs() }

    private val integratedFilterPackRoot: File
        get() = File(context.filesDir, "filter_packs").apply { mkdirs() }

    /** 导入失败用可读中文错误 */
    class ImportException(message: String) : Exception(message)

    // ---- 文件通道 ----

    /** 从 SAF Uri 导入 .crx/.zip/.user.js 文件。返回 extId 或抛 [ImportException] */
    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw ImportException("Cannot read selected file")
        } ?: throw ImportException("Cannot read selected file")
        val name = displayName(uri)
        stream.use {
            if (isUserScriptName(name)) {
                importUserScript(it, name)
            } else {
                importFromStream(it, name)
            }
        }
    }

    /** .user.js / .userjs 用户脚本（纯前端注入，无需解压）。按后缀判断，非最后一段扩展名 */
    private fun isUserScriptName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".user.js") || n.endsWith(".userjs")
    }

    // ---- 用户脚本通道 ----

    /**
     * 导入 .user.js 用户脚本：解析头部元数据，脚本本体存
     * filesDir/extensions/<id>/userscript.js，contentScriptsJson 引用它。
     * @require 外部库（如 JSZip）下载到 require/ 目录，注入时内联在脚本前。
     * @return extId（脚本内容 SHA-256 前 16 字节 hex）
     */
    suspend fun importUserScript(stream: InputStream, name: String): String = withContext(Dispatchers.IO) {
        val text = try {
            stream.readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ImportException("Cannot read UserScript file")
        }
        if (text.length > MAX_USER_SCRIPT_BYTES) throw ImportException("UserScript file too large")
        val extId = sha256Hex(text.toByteArray()).substring(0, 32)
        val fallbackName = name.substringAfterLast('/').removeSuffix(".user.js").removeSuffix(".js").ifBlank { "未命名脚本" }
        val meta = UserScriptParser.parse(text, fallbackName).getOrElse {
            throw ImportException("Failed to parse UserScript: ${it.message}")
        }

        // 存脚本本体
        val destDir = File(extRoot, extId)
        destDir.mkdirs()
        val scriptFile = File(destDir, "userscript.js")
        scriptFile.writeText(text)

        // 下载 @require 外部库（按顺序，失败不阻断导入——注入时缺库可降级）
        val requireDir = File(destDir, "require")
        requireDir.mkdirs()
        requireDir.listFiles()?.forEach { it.delete() } // 重装清空旧 require
        val requireFiles = mutableListOf<String>()
        meta.requires.forEachIndexed { idx, url ->
            try {
                val bytes = client.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { resp -> if (resp.isSuccessful) resp.body.bytes() else null }
                if (bytes != null && bytes.size > 0 && bytes.size <= MAX_ENTRY_BYTES) {
                    val f = File(requireDir, "$idx.js")
                    f.writeBytes(bytes)
                    requireFiles.add("require/$idx.js")
                    LogStore.log(LogCategory.FILTER, "UserScript @require downloaded: ${f.name} (${bytes.size} B)")
                } else {
                    LogStore.log(LogCategory.FILTER, "UserScript @require failed/skipped: $url")
                }
            } catch (e: Exception) {
                LogStore.error("UserScript @require download failed: $url", e)
            }
        }

        // 用户脚本 = 单条内联内容脚本（run_at 映射到 manifest 取值）
        val csJson = JSONArray().put(
            JSONObject()
                .put("matches", JSONArray(meta.matches))
                .put("js", JSONArray(listOf("userscript.js")))
                .put("css", JSONArray())
                .put("run_at", meta.runAt)
                .put("all_frames", meta.noframes)
        )
        // @require 注入放 manifest（bundlesFor 按 require 字段内联到脚本前）
        val manifestJson = JSONObject()
            .put("name", meta.name)
            .put("version", meta.version)
            .put("manifest_version", 0)
            .put("requires", JSONArray(requireFiles))
        File(destDir, "manifest.json").writeText(manifestJson.toString())
        val permsJson = JSONArray()

        // 保留原 enabled 状态（重装不重置开关）
        val prev = repo.getById(extId)
        repo.insert(
            ExtensionEntity(
                id = extId,
                name = meta.name,
                version = meta.version,
                manifestVersion = 0, // 用户脚本无 manifest_version
                description = meta.description,
                enabled = prev?.enabled ?: true,
                source = SOURCE_USERSCRIPT,
                optionsPage = "",
                iconPath = "",
                contentScriptsJson = csJson.toString(),
                permissionsJson = permsJson.toString(),
                homepageUrl = meta.homepageUrl,
                author = meta.author,
                installedAt = System.currentTimeMillis(),
            )
        )
        LogStore.log(LogCategory.FILTER, "UserScript imported: ${meta.name} v${meta.version} ($extId)")
        extId
    }

    private fun displayName(uri: Uri): String {
        val c = context.contentResolver
        return c.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else "" } ?: ""
    }

    /**
     * 6→7 数据库迁移曾把所有存量商店扩展统一标成 CHROME。对其中包清单明确
     * 指向 Edge 更新服务的记录做一次自愈；只修正 CHROME→EDGE，避免覆盖导入时
     * 已经由商店链接明确记录的来源。
     */
    suspend fun repairLegacyStoreSources() = withContext(Dispatchers.IO) {
        repo.getAll().forEach { ext ->
            if (ext.manifestVersion == 0 || ext.source != SOURCE_CHROME) return@forEach
            val manifestFile = File(extRoot, "${ext.id}/manifest.json")
            if (!manifestFile.isFile) return@forEach
            val detected = runCatching {
                detectStoreSource(JSONObject(manifestFile.readText()))
            }.getOrNull()
            if (detected == SOURCE_EDGE) {
                repo.setSource(ext.id, SOURCE_EDGE)
                LogStore.log(LogCategory.FILTER, "Corrected extension source to Edge: ${ext.name}")
            }
        }
    }

    // ---- 链接/ID 通道 ----

    /**
     * 从输入导入扩展。支持三种形态：
     *  1. Chrome 商店链接（chromewebstore.google.com / chrome.google.com/webstore）
     *  2. 裸扩展 ID（32 位 [a-p0-9]，Chrome 扩展 ID 用 a-p 而非全 hex）
     *  3. 直链（.crx / .zip 文件 URL）
     * 1/2 走官方 update2 更新端点拉包（Chrome 自身拉取 CRX 的方式）。
     */
    suspend fun importFromUrl(input: String): String = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) throw ImportException("Please enter extension URL or ID")
        when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> {
                if (isUserScriptName(trimmed.substringBefore('?'))) {
                    // .user.js 直链：直接下载当用户脚本导入
                    downloadUserScript(trimmed)
                } else if (trimmed.endsWith(".crx", ignoreCase = true) ||
                    trimmed.endsWith(".zip", ignoreCase = true)
                ) {
                    // 直链：直接下载
                    downloadTo(trimmed)
                } else if (trimmed.contains("microsoftedge.microsoft.com")) {
                    // Edge 商店链接：走 Edge 官方 CDN 端点
                    val id = extractStoreId(trimmed)
                        ?: throw ImportException("Cannot parse extension ID from Edge URL: $trimmed")
                    fetchFromEdge(id)
                } else {
                    // 商店链接：提取 ID
                    val id = extractStoreId(trimmed)
                        ?: throw ImportException("Cannot parse extension ID from URL: $trimmed")
                    fetchFromUpdate2(id)
                }
            }
            isExtensionId(trimmed) -> fetchFromUpdate2(trimmed.lowercase(Locale.US))
            else -> throw ImportException("Unrecognized extension URL or ID")
        }
    }

    /** 从商店链接提取 32 位扩展 ID（Chrome / Edge URL 末尾段） */
    private fun extractStoreId(url: String): String? {
        val re = Regex(
            "(?:chromewebstore\\.google\\.com/detail/|" +
                "chrome\\.google\\.com/webstore/detail/|" +
                "microsoftedge\\.microsoft\\.com/addons/detail/)" +
                "[^/]+/([a-p0-9]{32})",
        )
        val m = re.find(url)
        if (m != null) return m.groupValues[1]
        // 兜底：URL 中任意 [a-p0-9]{32}
        return Regex("[a-p0-9]{32}").find(url)?.value
    }

    private fun isExtensionId(s: String): Boolean = Regex("^[a-p0-9]{32}$").matches(s)

    /**
     * 走 Edge 官方 CDN 端点拉取 CRX（Edge 扩展商店分发同款 Chrome 扩展）。
     * 国内网络下 google 的 clients2.update 常被墙，Edge 端点更稳。
     */
    private suspend fun fetchFromEdge(id: String): String {
        val url = ("https://edge.microsoft.com/extensionwebstorebase/v1/crx" +
            "?response=redirect" +
            "&x=id%3D$id%26installsource%3Dondemand%26uc")
        return try {
            val body = client.newCall(Request.Builder().url(url).build())
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        LogStore.log(LogCategory.DOWNLOAD, "Edge fetch failed HTTP ${resp.code}")
                        throw ImportException("Failed to fetch extension from Edge store (HTTP ${resp.code})")
                    }
                    resp.body.bytes()
                }
            if (body.size < 64) {
                throw ImportException("Edge store returned empty package")
            }
            body.inputStream().use { importFromStream(it, "$id.crx", source = SOURCE_EDGE) }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            LogStore.error("Edge fetch exception", e)
            throw ImportException("Failed to fetch extension from Edge store")
        }
    }

    /** 走官方更新端点拉取 CRX（prodversion 用真机 WebView 版本，Chrome 扩展拉取同款端点） */
    private suspend fun fetchFromUpdate2(id: String): String {
        val prodVersion = WEBVIEW_VERSION
        val url = ("https://clients2.google.com/service/update2/crx" +
            "?response=redirect" +
            "&prodversion=$prodVersion" +
            "&acceptformat=crx3" +
            "&x=id%3D$id%26installsource%3Dondemand%26uc")
        return try {
            val body = client.newCall(Request.Builder().url(url).build())
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) {
                        LogStore.log(LogCategory.DOWNLOAD, "update2 fetch failed HTTP ${resp.code}")
                        throw ImportException("Failed to fetch extension from store (HTTP ${resp.code})")
                    }
                    resp.body.bytes()
                }
            if (body.size < 64) {
                throw ImportException("Store returned empty package")
            }
            body.inputStream().use { importFromStream(it, "$id.crx", source = SOURCE_CHROME) }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            LogStore.error("update2 fetch exception", e)
            throw ImportException("Failed to fetch extension from store")
        }
    }

    /** 下载直链到 cacheDir，再走同一套解压注册 */
    private suspend fun downloadTo(url: String): String {
        val bytes = try {
            client.newCall(Request.Builder().url(url).build())
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) throw ImportException("Download failed (HTTP ${resp.code})")
                    resp.body.bytes()
                }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Download failed: ${e.message}")
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.crx" }
        return bytes.inputStream().use { importFromStream(it, name) }
    }

    /** 下载 .user.js 直链，走用户脚本导入管线 */
    private suspend fun downloadUserScript(url: String): String {
        val bytes = try {
            client.newCall(Request.Builder().url(url).build())
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) throw ImportException("Download failed (HTTP ${resp.code})")
                    resp.body.bytes()
                }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Download failed: ${e.message}")
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "script.user.js" }
        return bytes.inputStream().use { importUserScript(it, name) }
    }

    // ---- 公共解压注册管线 ----

    /**
     * 从任意流解压注册扩展。
     * @param source 已知的导入来源；本地包/直链未指定时根据 manifest.update_url 判定。
     * @return extId（包 SHA-256 前 16 字节 hex，32 字符）
     */
    private suspend fun importFromStream(stream: InputStream, name: String, source: String? = null): String {
        val bytes = stream.readBytes()
        val extId = sha256Hex(bytes).substring(0, 32)
        val zipBytes = extractZipBytes(bytes)
        val destDir = File(extRoot, extId)

        // 解压（临时目录 → 校验清单 → 原子换目录）
        val tmp = File(extRoot, "$extId.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            unpackZip(ZipInputStream(BufferedInputStream(zipBytes.inputStream())), tmp)
            val manifestFile = File(tmp, "manifest.json")
            if (!manifestFile.isFile) throw ImportException("Extension package missing manifest.json")
            val parsed = ManifestParser.parse(manifestFile.readText())
                .getOrElse { throw ImportException("Failed to parse manifest: ${it.message}") }
            // 消息本地化：__MSG_name__ 等；解析后的消息存盘供 shim 的 i18n.getMessage 使用
            val deviceLang = Locale.getDefault().language
            val manifestJson = JSONObject(manifestFile.readText())
            val resolvedSource = source ?: detectStoreSource(manifestJson) ?: SOURCE_CHROME
            val defaultLocale = manifestJson.optString("default_locale", "")
            val messages = ManifestParser.resolveMessages(
                File(tmp, "_locales"), defaultLocale, deviceLang
            )
            if (messages != null) {
                File(tmp, "_xv_messages.json").writeText(messages.toString())
            }
            val nameLoc = ManifestParser.localize(parsed.name, messages)
            val descLoc = ManifestParser.localize(parsed.description, messages)
            val optionsLoc = ManifestParser.localize(parsed.optionsPage, messages)

            // 过滤扩展的后台页无法在 WebView 中运行；若原包包含可识别的默认过滤库，
            // 从用户下载的原版数据生成 XVerse 原生过滤索引，供设置页独立开关。
            val nativeFilterPack = ExtensionFilterPackStore.buildIfSupported(
                extensionDir = tmp,
                extensionId = extId,
                manifest = manifestJson,
                localizedName = nameLoc,
                locale = Locale.getDefault(),
                packageZip = zipBytes,
            )

            // 图标复制到固定 icon.png
            val iconRel = ManifestParser.pickIcon(parsed.icons)
            var iconPath = ""
            if (iconRel != null) {
                val rel = safeRel(iconRel)
                if (rel == "icon.png") {
                    // 已是固定名，直接引用
                    if (File(tmp, rel).isFile) iconPath = "icon.png"
                } else {
                    val src = File(tmp, rel)
                    if (src.isFile) {
                        src.copyTo(File(tmp, "icon.png"), overwrite = true)
                        iconPath = "icon.png"
                    }
                }
            }

            // 序列化 content_scripts / permissions
            val csJson = JSONArray()
            parsed.contentScripts.forEach { c ->
                csJson.put(
                    JSONObject()
                        .put("matches", JSONArray(c.matches))
                        .put("js", JSONArray(c.js))
                        .put("css", JSONArray(c.css))
                        .put("run_at", c.runAt)
                        .put("all_frames", c.allFrames)
                )
            }
            val permsJson = JSONArray()
            parsed.permissions.forEach { permsJson.put(it) }

            if (parsed.hasBackground) {
                LogStore.log(
                    LogCategory.FILTER,
                    "Extension $nameLoc v${parsed.version} has background script ${parsed.background} (unsupported in WebView, injecting content scripts only)"
                )
            }

            // 原子替换：旧目录清掉（同 id 重装）
            val old = File(extRoot, extId)
            if (old.isDirectory) old.deleteRecursively()
            if (!tmp.renameTo(old)) {
                // 极端情况 rename 失败，回退复制
                old.mkdirs()
                tmp.listFiles()?.forEach { f -> f.copyRecursively(File(old, f.name), overwrite = true) }
                tmp.deleteRecursively()
            }

            // 规则索引属于设置页的原生广告过滤模块，不跟随扩展的启停、配置或卸载。
            if (nativeFilterPack != null &&
                !ExtensionFilterPackStore.detachBuiltPack(old, integratedFilterPackRoot)
            ) {
                LogStore.log(LogCategory.FILTER, "Rule index deferred, will retry on first read in settings")
            }

            // 保留原 enabled 状态（重装不重置开关）
            val prev = repo.getById(extId)
            repo.insert(
                ExtensionEntity(
                    id = extId,
                    name = nameLoc,
                    version = parsed.version,
                    manifestVersion = parsed.manifestVersion,
                    description = descLoc,
                    // 后台型过滤扩展由原生规则行接管；不默认注入其依赖后台状态的内容脚本。
                    enabled = if (nativeFilterPack != null) false else prev?.enabled ?: true,
                    source = resolvedSource,
                    optionsPage = optionsLoc,
                    iconPath = iconPath,
                    contentScriptsJson = csJson.toString(),
                    permissionsJson = permsJson.toString(),
                    homepageUrl = parsed.homepageUrl,
                    author = parsed.author,
                    installedAt = System.currentTimeMillis(),
                )
            )
            LogStore.log(LogCategory.FILTER, "Extension imported: $nameLoc v${parsed.version} ($extId)")
            return extId
        } catch (e: ImportException) {
            tmp.deleteRecursively()
            throw e
        } catch (e: Exception) {
            tmp.deleteRecursively()
            LogStore.error("Failed to unpack extension: $name", e)
            throw ImportException("Failed to unpack extension: ${e.message}")
        }
    }

    /**
     * 提取 ZIP 数据：CRX（Cr24）跳过头部定位 ZIP 起点；否则整文件当 ZIP。
     * CRX 头部：4 字节 magic + 4 字节 version + 4 字节 header_len，
     * header 之后紧跟 ZIP（定位方式：从偏移 8 起扫描 PK\x03\x04 本地文件头）。
     */
    private fun extractZipBytes(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) throw ImportException("File too small, invalid extension package")
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != "Cr24") {
            // 裸 ZIP（PK\x03\x04 开头）或未知格式
            if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                return bytes
            }
            throw ImportException("Invalid extension package (missing Cr24/ZIP header)")
        }
        // CRX v2/v3：header_len 在偏移 8 前是 version(4)+header_len(4)
        var offset = 8
        while (offset + 4 <= bytes.size) {
            if (bytes[offset] == 'P'.code.toByte() && bytes[offset + 1] == 'K'.code.toByte() &&
                bytes[offset + 2] == 0x03.toByte() && bytes[offset + 3] == 0x04.toByte()
            ) {
                return bytes.copyOfRange(offset, bytes.size)
            }
            offset++
        }
        throw ImportException("ZIP data not found inside CRX")
    }

    /**
     * 解压 ZIP 到目录，带路径穿越与体积防护。
     *
     * WebView 不执行后台脚本（MV3 service worker / MV2 background），
     * MV3 声明式规则集与 _metadata 校验数据体积巨大，跳过不落盘；可支持的默认规则
     * 由 ExtensionFilterPackStore 直接从原始 ZIP 流式提取。AdGuard MV2 的 filters
     * 目录文本会先临时展开，再从文本规则中提取默认规则为紧凑索引，
     * 随后删除原始过滤库：
     * 只保留内容脚本 / 图标 / options / _locales / background 文件等注入相关部分。
     */
    private fun unpackZip(zip: ZipInputStream, dest: File) {
        var count = 0
        var total = 0L
        var skipped = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            count++
            if (count > MAX_ENTRIES) throw ImportException("Too many files in package")
            val rel = safeRel(entry.name)
            if (rel.isEmpty()) { zip.closeEntry(); continue }
            // 跳过用不到的巨型数据：declarativeNetRequest 规则集、_metadata 校验清单
            val norm = rel.lowercase()
            if (norm.startsWith("filters/declarative/") || norm.startsWith("_metadata/")) {
                if (entry.isDirectory) skipped++
                else skipped += entry.size
                zip.closeEntry()
                continue
            }
            val target = File(dest, rel)
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                var written = 0L
                target.outputStream().buffered().use { out ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > MAX_ENTRY_BYTES) throw ImportException("Single file exceeds size limit")
                        out.write(buffer, 0, n)
                    }
                }
                total += written
                if (total > MAX_UNPACK_BYTES) throw ImportException("Unpacked size exceeds limit")
            }
            zip.closeEntry()
        }
        if (skipped > 0) {
            LogStore.log(
                LogCategory.FILTER,
                "Extension extract skipped declarative rules/validation ${skipped / 1024} KB (not needed for WebView)"
            )
        }
    }

    /** 校验相对路径安全，返回规范化相对路径（空串表示非法/根目录） */
    private fun safeRel(name: String): String {
        var n = name.replace('\\', '/')
        if (n.startsWith("/")) return ""
        // 去掉开头的 ./ 与重复斜杠
        while (n.startsWith("./")) n = n.removePrefix("./")
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return ""
        if (parts.any { it == ".." }) return ""
        return parts.joinToString("/")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun detectStoreSource(manifest: JSONObject): String? {
        val updateUrl = manifest.optString("update_url").lowercase(Locale.US)
        return when {
            "edge.microsoft.com" in updateUrl || "microsoftedge.microsoft.com" in updateUrl -> SOURCE_EDGE
            "clients2.google.com" in updateUrl || "chromewebstore.google.com" in updateUrl -> SOURCE_CHROME
            else -> null
        }
    }

    companion object {
        private const val MAX_ENTRIES = 2000
        // AdGuard Edge MV2 原包约 65MB；允许临时展开后提取默认规则并立即压缩为索引。
        private const val MAX_UNPACK_BYTES = 80L * 1024 * 1024
        // 单文件上限 20MB：主流过滤/翻译扩展压缩前的单文件通常 ≤ 12MB
        // （如 immersive-translate 的 ort-wasm-simd-threaded.wasm 10.7MB）
        private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
        // 沉浸式翻译官方油猴版约 4.5MB；保留余量同时限制异常大脚本。
        private const val MAX_USER_SCRIPT_BYTES = 8L * 1024 * 1024

        /** 用户脚本来源标记（扩展列表分组用） */
        const val SOURCE_USERSCRIPT = "USERSCRIPT"
        const val SOURCE_CHROME = "CHROME"
        const val SOURCE_EDGE = "EDGE"

        /** 真机系统 WebView 版本（update2 端点要求 prodversion 为 Chromium 版本号） */
        private const val WEBVIEW_VERSION = "149.0.7827.159"
    }
}

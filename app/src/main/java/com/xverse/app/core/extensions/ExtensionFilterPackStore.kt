package com.xverse.app.core.extensions

import android.util.JsonReader
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.TreeSet
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从用户自行下载的过滤扩展中提取默认规则，生成 XVerse 原生过滤器可读取的紧凑索引。
 *
 * 支持 AdGuard MV2 文本规则与 MV3 declarativeNetRequest 规则集。
 * 不改写扩展脚本，也不把规则打进 APK；索引只存在于用户设备的扩展私有目录。
 */
object ExtensionFilterPackStore {
    private const val PACK_DIR = "_xv_filter_pack"
    private const val META_FILE = "meta.json"
    private const val BLOCK_FILE = "blocked-hosts.txt"
    private const val ALLOW_FILE = "allowed-hosts.txt"
    private const val CSS_FILE = "x-css.txt"

    data class Summary(
        val extensionId: String,
        val name: String,
        val version: String,
        val blockedCount: Int,
        val allowedCount: Int,
        val cssCount: Int,
        val groups: List<FilterGroupSummary> = emptyList(),
    ) {
        val ruleCount: Int get() = blockedCount + allowedCount + cssCount
    }

    data class FilterGroupSummary(
        val filterId: Int,
        val name: String,
        val blockedCount: Int,
        val allowedCount: Int,
        val cssCount: Int,
    ) {
        val ruleCount: Int get() = blockedCount + allowedCount + cssCount
    }

    data class Pack(
        val summary: Summary,
        val blockedHosts: Set<String>,
        val allowedHosts: Set<String>,
        val cssSelectors: List<String>,
    )

    private data class RuleGroup(
        val blocked: TreeSet<String> = TreeSet(),
        val allowed: TreeSet<String> = TreeSet(),
        val css: TreeSet<String> = TreeSet(),
    )

    /**
     * 将导入扩展目录内刚生成的规则索引迁到独立过滤目录。迁移后规则生命周期与
     * 扩展文件解耦，扩展启停、配置或卸载都不会改变设置页中的集成规则。
     */
    @Synchronized
    fun detachBuiltPack(extensionDir: File, integratedRoot: File): Boolean {
        val source = File(extensionDir, PACK_DIR)
        if (!source.isDirectory) return false
        val summary = readSummary(extensionDir) ?: return false
        integratedRoot.mkdirs()
        val staged = File(integratedRoot, "${summary.extensionId}.tmp")
        val target = File(integratedRoot, summary.extensionId)
        staged.deleteRecursively()
        val stagedReady = source.renameTo(staged) || runCatching {
            source.copyRecursively(staged, overwrite = true)
            true
        }.getOrDefault(false)
        if (!stagedReady) return false
        target.deleteRecursively()
        val installed = staged.renameTo(target) || runCatching {
            staged.copyRecursively(target, overwrite = true)
            staged.deleteRecursively()
            true
        }.getOrDefault(false)
        if (installed) source.deleteRecursively()
        return installed
    }

    /** 兼容已安装版本：首次读取设置时自动剥离旧扩展目录中的规则包。 */
    @Synchronized
    fun migrateLegacyPacks(extensionRoot: File, integratedRoot: File) {
        extensionRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { extensionDir ->
                if (File(extensionDir, PACK_DIR).isDirectory) {
                    detachBuiltPack(extensionDir, integratedRoot)
                }
            }
    }

    /** 识别并提取扩展的默认规则；不支持的包返回 null。 */
    fun buildIfSupported(
        extensionDir: File,
        extensionId: String,
        manifest: JSONObject,
        localizedName: String,
        locale: Locale,
        packageZip: ByteArray,
    ): Summary? {
        val filtersDir = File(extensionDir, "filters")
        val metadataFile = File(filtersDir, "filters.json")
        return if (metadataFile.isFile) {
            buildFromMv2(
                extensionDir = extensionDir,
                extensionId = extensionId,
                manifest = manifest,
                localizedName = localizedName,
                locale = locale,
                filtersDir = filtersDir,
                metadataFile = metadataFile,
            )
        } else {
            buildFromMv3(
                extensionDir = extensionDir,
                extensionId = extensionId,
                manifest = manifest,
                localizedName = localizedName,
                locale = locale,
                packageZip = packageZip,
            )
        }
    }

    private fun buildFromMv2(
        extensionDir: File,
        extensionId: String,
        manifest: JSONObject,
        localizedName: String,
        locale: Locale,
        filtersDir: File,
        metadataFile: File,
    ): Summary? {
        val metadata = runCatching { JSONObject(metadataFile.readText()) }.getOrNull() ?: return null
        val filters = metadata.optJSONArray("filters") ?: return null

        // 与 AdGuard 首次安装默认值保持一致：基础、搜索广告/自推广例外、移动广告，
        // 再加与设备语言匹配的推荐语言过滤器。
        val selectedIds = linkedSetOf(2, 10, 11)
        val language = locale.language.lowercase()
        for (i in 0 until filters.length()) {
            val item = filters.optJSONObject(i) ?: continue
            if (item.optBoolean("deprecated", false)) continue
            val languages = item.optJSONArray("languages") ?: JSONArray()
            var languageMatch = false
            for (j in 0 until languages.length()) {
                if (languages.optString(j).lowercase() == language) {
                    languageMatch = true
                    break
                }
            }
            if (languageMatch) selectedIds += item.optInt("filterId")
        }

        val groups = linkedMapOf<Int, RuleGroup>()
        val hostRule = Regex("^(@@)?\\|\\|([A-Za-z0-9.-]+)\\^(?:\\${'$'}third-party)?${'$'}")

        selectedIds.forEach { id ->
            val mobile = File(filtersDir, "filter_mobile_${id}.txt")
            val desktop = File(filtersDir, "filter_${id}.txt")
            val source = mobile.takeIf(File::isFile) ?: desktop.takeIf(File::isFile) ?: return@forEach
            val group = groups.getOrPut(id) { RuleGroup() }
            source.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    val hostMatch = hostRule.matchEntire(line)
                    if (hostMatch != null) {
                        val host = hostMatch.groupValues[2].lowercase()
                        if (host.isNotBlank()) {
                            if (hostMatch.groupValues[1].isNotEmpty()) group.allowed += host else group.blocked += host
                        }
                        return@forEach
                    }
                    val marker = line.indexOf("##")
                    if (marker <= 0 || line.contains("#@#") || line.contains("#${'$'}#") || line.contains("#%#")) {
                        return@forEach
                    }
                    val domains = line.substring(0, marker).split(',').map(String::trim)
                    val appliesToX = domains.any { it == "x.com" || it == "twitter.com" }
                    val excludedFromX = domains.any { it == "~x.com" || it == "~twitter.com" }
                    val selector = line.substring(marker + 2).trim()
                    if (appliesToX && !excludedFromX && selector.isNotEmpty() && selector.length <= 2_000) {
                        group.css += selector
                    }
                }
            }
        }
        val blocked = groups.values.flatMapTo(TreeSet<String>()) { it.blocked }
        val allowed = groups.values.flatMapTo(TreeSet<String>()) { it.allowed }
        val css = groups.values.flatMapTo(TreeSet<String>()) { it.css }
        blocked.removeAll(allowed)
        if (blocked.isEmpty() && allowed.isEmpty() && css.isEmpty()) return null

        val summary = writePack(
            extensionDir = extensionDir,
            extensionId = extensionId,
            manifest = manifest,
            localizedName = localizedName,
            selectedIds = selectedIds,
            blocked = blocked,
            allowed = allowed,
            css = css,
            groups = groups,
            format = "adguard-mv2",
        )

        // 后台页无法在 WebView 中运行，完整原始过滤库不会被其消费；紧凑索引生成后删除
        // 这些临时展开文件，保留扩展其余原版文件与 LICENSE/NOTICE。
        filtersDir.deleteRecursively()
        LogStore.log(
            LogCategory.FILTER,
            "Extracted ${summary.name} default rules: ${summary.ruleCount} items (filters ${selectedIds.joinToString()})",
        )
        return summary
    }

    /**
     * MV3 商店包把所有过滤器放在巨型 DNR JSON 中。只流式读取默认启用的基础规则、
     * Android 移动规则和设备语言规则，不把 JSON 完整展开到磁盘或一次性载入内存。
     */
    private fun buildFromMv3(
        extensionDir: File,
        extensionId: String,
        manifest: JSONObject,
        localizedName: String,
        locale: Locale,
        packageZip: ByteArray,
    ): Summary? {
        if (manifest.optInt("manifest_version") < 3) return null
        val resources = manifest.optJSONObject("declarative_net_request")
            ?.optJSONArray("rule_resources") ?: return null
        val pathsById = linkedMapOf<Int, String>()
        val manifestEnabledIds = linkedSetOf<Int>()
        for (i in 0 until resources.length()) {
            val item = resources.optJSONObject(i) ?: continue
            val path = item.optString("path").replace('\\', '/').removePrefix("./")
            val pathMatch = ADGUARD_DNR_PATH.matchEntire(path) ?: continue
            val id = item.optString("id").substringAfterLast('_').toIntOrNull() ?: continue
            if (pathMatch.groupValues[1].toIntOrNull() != id || pathMatch.groupValues[2].toIntOrNull() != id) {
                continue
            }
            pathsById[id] = path
            if (item.optBoolean("enabled", false)) manifestEnabledIds += id
        }
        if (2 !in pathsById) return null

        // AdGuard Android 首次安装：清单默认规则 + 搜索广告例外 + 移动广告 + 语言规则。
        val selectedIds = linkedSetOf<Int>().apply {
            addAll(manifestEnabledIds)
            if (10 in pathsById) add(10)
            if (11 in pathsById) add(11)
            LANGUAGE_FILTER_IDS[locale.language.lowercase()]?.let { if (it in pathsById) add(it) }
        }
        val selectedPaths = selectedIds.mapNotNull { id -> pathsById[id]?.let { it to id } }.toMap()
        if (selectedPaths.isEmpty()) return null

        val groups = linkedMapOf<Int, RuleGroup>()
        val foundIds = linkedSetOf<Int>()
        ZipInputStream(BufferedInputStream(packageZip.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').removePrefix("./")
                val filterId = selectedPaths[path]
                if (!entry.isDirectory && filterId != null) {
                    val group = groups.getOrPut(filterId) { RuleGroup() }
                    JsonReader(InputStreamReader(zip, Charsets.UTF_8)).let { reader ->
                        parseDnrRules(reader, group.blocked, group.allowed)
                    }
                    foundIds += filterId
                }
                zip.closeEntry()
            }
        }
        val blocked = groups.values.flatMapTo(TreeSet<String>()) { it.blocked }
        val allowed = groups.values.flatMapTo(TreeSet<String>()) { it.allowed }
        if (blocked.isEmpty() && allowed.isEmpty()) return null
        // 例外优先，避免同一个父域同时出现在阻止与放行索引时误拦截。
        blocked.removeAll(allowed)
        return writePack(
            extensionDir = extensionDir,
            extensionId = extensionId,
            manifest = manifest,
            localizedName = localizedName,
            selectedIds = foundIds,
            blocked = blocked,
            allowed = allowed,
            css = emptySet(),
            groups = groups,
            format = "adguard-mv3-dnr",
        ).also { summary ->
            LogStore.log(
                LogCategory.FILTER,
                "Stream-extracted ${summary.name} MV3 default rules: ${summary.ruleCount} items (filters ${foundIds.joinToString()})",
            )
        }
    }

    private fun parseDnrRules(
        reader: JsonReader,
        blocked: MutableSet<String>,
        allowed: MutableSet<String>,
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            var actionType = ""
            var urlFilter = ""
            var simpleCondition = false
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "action" -> actionType = readActionType(reader)
                    "condition" -> {
                        val condition = readSimpleCondition(reader)
                        urlFilter = condition.first
                        simpleCondition = condition.second
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (!simpleCondition) continue
            val match = DNR_HOST_RULE.matchEntire(urlFilter) ?: continue
            val host = match.groupValues[1].lowercase()
            when (actionType) {
                "block" -> blocked += host
                "allow", "allowAllRequests" -> allowed += host
            }
        }
        reader.endArray()
    }

    private fun readActionType(reader: JsonReader): String {
        var type = ""
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "type") type = reader.nextString() else reader.skipValue()
        }
        reader.endObject()
        return type
    }

    /** 只接受无域名/资源类型约束的纯主机规则，宁可少拦截也不改变原规则语义。 */
    private fun readSimpleCondition(reader: JsonReader): Pair<String, Boolean> {
        var urlFilter = ""
        var onlyUrlFilter = true
        var fieldCount = 0
        reader.beginObject()
        while (reader.hasNext()) {
            fieldCount++
            when (reader.nextName()) {
                "urlFilter" -> urlFilter = reader.nextString()
                else -> {
                    onlyUrlFilter = false
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        return urlFilter to (onlyUrlFilter && fieldCount == 1)
    }

    private fun writePack(
        extensionDir: File,
        extensionId: String,
        manifest: JSONObject,
        localizedName: String,
        selectedIds: Collection<Int>,
        blocked: Collection<String>,
        allowed: Collection<String>,
        css: Collection<String>,
        groups: Map<Int, RuleGroup>,
        format: String,
    ): Summary {
        val packDir = File(extensionDir, PACK_DIR).apply { mkdirs() }
        File(packDir, BLOCK_FILE).bufferedWriter().use { out -> blocked.forEach { out.appendLine(it) } }
        File(packDir, ALLOW_FILE).bufferedWriter().use { out -> allowed.forEach { out.appendLine(it) } }
        File(packDir, CSS_FILE).bufferedWriter().use { out -> css.forEach { out.appendLine(it) } }
        val groupSummaries = groups.map { (filterId, group) ->
            val groupDir = File(File(packDir, "groups"), filterId.toString()).apply { mkdirs() }
            File(groupDir, BLOCK_FILE).bufferedWriter().use { out -> group.blocked.forEach { out.appendLine(it) } }
            File(groupDir, ALLOW_FILE).bufferedWriter().use { out -> group.allowed.forEach { out.appendLine(it) } }
            File(groupDir, CSS_FILE).bufferedWriter().use { out -> group.css.forEach { out.appendLine(it) } }
            FilterGroupSummary(
                filterId = filterId,
                name = filterDisplayName(filterId),
                blockedCount = group.blocked.size,
                allowedCount = group.allowed.size,
                cssCount = group.css.size,
            )
        }
        val summary = Summary(
            extensionId = extensionId,
            name = localizedName,
            version = manifest.optString("version"),
            blockedCount = blocked.size,
            allowedCount = allowed.size,
            cssCount = css.size,
            groups = groupSummaries,
        )
        val groupsJson = JSONArray().apply {
            groupSummaries.forEach { group ->
                put(
                    JSONObject()
                        .put("filterId", group.filterId)
                        .put("name", group.name)
                        .put("blockedCount", group.blockedCount)
                        .put("allowedCount", group.allowedCount)
                        .put("cssCount", group.cssCount)
                )
            }
        }
        File(packDir, META_FILE).writeText(
            JSONObject()
                .put("extensionId", summary.extensionId)
                .put("name", summary.name)
                .put("version", summary.version)
                .put("blockedCount", summary.blockedCount)
                .put("allowedCount", summary.allowedCount)
                .put("cssCount", summary.cssCount)
                .put("format", format)
                .put("defaultFilterIds", JSONArray(selectedIds.toList()))
                .put("groups", groupsJson)
                .toString(),
        )
        return summary
    }

    fun summaries(integratedRoot: File): List<Summary> = integratedRoot.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { readSummary(it) }

    fun loadAll(integratedRoot: File, disabledGroupKeys: Set<String> = emptySet()): List<Pack> = integratedRoot.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { dir ->
            val summary = readSummary(dir) ?: return@mapNotNull null
            val packDir = resolvePackDir(dir)
            val enabledGroups = summary.groups.filterNot { group ->
                "${summary.extensionId}:${group.filterId}" in disabledGroupKeys
            }
            val useGroupedFiles = summary.groups.isNotEmpty() && enabledGroups.size != summary.groups.size
            val blocked = if (useGroupedFiles) TreeSet<String>() else readSet(File(packDir, BLOCK_FILE)).toMutableSet()
            val allowed = if (useGroupedFiles) TreeSet<String>() else readSet(File(packDir, ALLOW_FILE)).toMutableSet()
            val css = if (useGroupedFiles) linkedSetOf<String>() else readSet(File(packDir, CSS_FILE)).toMutableSet()
            if (useGroupedFiles) {
                enabledGroups.forEach { group ->
                    val groupDir = File(File(packDir, "groups"), group.filterId.toString())
                    blocked += readSet(File(groupDir, BLOCK_FILE))
                    allowed += readSet(File(groupDir, ALLOW_FILE))
                    css += readSet(File(groupDir, CSS_FILE))
                }
                blocked.removeAll(allowed)
            }
            Pack(
                summary = summary,
                blockedHosts = blocked,
                allowedHosts = allowed,
                cssSelectors = css.toList(),
            )
        }

    private fun resolvePackDir(container: File): File {
        val directMeta = File(container, META_FILE)
        return if (directMeta.isFile) container else File(container, PACK_DIR)
    }

    private fun readSummary(container: File): Summary? {
        val file = File(resolvePackDir(container), META_FILE)
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val groupsJson = json.optJSONArray("groups") ?: JSONArray()
        val groups = buildList {
            for (i in 0 until groupsJson.length()) {
                val group = groupsJson.optJSONObject(i) ?: continue
                val filterId = group.optInt("filterId", -1)
                if (filterId < 0) continue
                add(
                    FilterGroupSummary(
                        filterId = filterId,
                        name = group.optString("name", filterDisplayName(filterId)),
                        blockedCount = group.optInt("blockedCount"),
                        allowedCount = group.optInt("allowedCount"),
                        cssCount = group.optInt("cssCount"),
                    )
                )
            }
        }
        return Summary(
            extensionId = json.optString("extensionId", container.name),
            name = json.optString("name", "扩展默认规则"),
            version = json.optString("version"),
            blockedCount = json.optInt("blockedCount"),
            allowedCount = json.optInt("allowedCount"),
            cssCount = json.optInt("cssCount"),
            groups = groups,
        )
    }

    private fun readSet(file: File): Set<String> = if (!file.isFile) {
        emptySet()
    } else {
        file.useLines { lines -> lines.map(String::trim).filter(String::isNotEmpty).toSet() }
    }

    private val DNR_HOST_RULE = Regex("^\\|\\|([A-Za-z0-9.-]+)\\^${'$'}")
    private val ADGUARD_DNR_PATH = Regex(
        "^filters/declarative/ruleset_(\\d+)/ruleset_(\\d+)\\.json${'$'}",
    )

    private val LANGUAGE_FILTER_IDS = mapOf(
        "ru" to 1,
        "de" to 6,
        "ja" to 7,
        "nl" to 8,
        "es" to 9,
        "pt" to 9,
        "tr" to 13,
        "fr" to 16,
        "uk" to 23,
        "zh" to 224,
    )

    private fun filterDisplayName(filterId: Int): String = when (filterId) {
        2 -> "AdGuard Base Filter"
        10 -> "Search Ads & Self-Promotion"
        11 -> "AdGuard Mobile Filter"
        224 -> "AdGuard Chinese Filter"
        else -> "AdGuard Filter $filterId"
    }
}

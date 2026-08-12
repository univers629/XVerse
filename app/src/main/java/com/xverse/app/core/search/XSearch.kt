package com.xverse.app.core.search

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Native state for the visual X advanced-search panel. */
data class XSearchFilterState(
    val keywords: String = "",
    val exactPhrase: String = "",
    val orTerms: String = "",
    val exclude: String = "",
    val hashtag: String = "",
    val url: String = "",
    val language: String = "",
    val from: String = "",
    val to: String = "",
    val near: String = "",
    val withinValue: String = "",
    val withinUnit: String = "km",
    val verifiedOnly: Boolean = false,
    val followingOnly: Boolean = false,
    val withinTimeValue: String = "",
    val withinTimeUnit: String = "d",
    val since: String = "",
    val until: String = "",
    val minFaves: String = "",
    val minRetweets: String = "",
    val minReplies: String = "",
    val filterMedia: Boolean = false,
    val filterImages: Boolean = false,
    val filterVideos: Boolean = false,
    val filterLinks: Boolean = false,
    val filterQuote: Boolean = false,
    val excludeReplies: Boolean = false,
)

data class XSearchBuildResult(
    val query: String,
    val hasTimeConflict: Boolean,
)

/**
 * Query syntax ported from X Search Filters 0.6.0. Only operators exposed by
 * that version's visible four-tab panel are represented here.
 */
object XSearchQuery {
    fun build(state: XSearchFilterState): XSearchBuildResult {
        val out = mutableListOf<String>()

        state.keywords.trim().takeIf(String::isNotEmpty)?.let(out::add)
        state.exactPhrase.trim().takeIf(String::isNotEmpty)?.let { raw ->
            val phrase = raw.removePrefix("\"").removeSuffix("\"")
            out += "\"${phrase.replace("\"", "\\\"")}\""
        }

        val orTerms = splitCsv(state.orTerms)
        when (orTerms.size) {
            0 -> Unit
            1 -> out += quoteIfNeeded(orTerms.first())
            else -> out += orTerms.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                quoteIfNeeded(it)
            }
        }
        splitCsv(state.exclude).forEach { out += "-${quoteIfNeeded(it)}" }

        state.hashtag.trim().removePrefix("#").takeIf(String::isNotEmpty)?.let { out += "#$it" }
        pushOperator(out, "from:", state.from.trim().removePrefix("@"))
        pushOperator(out, "to:", state.to.trim().removePrefix("@"))
        if (state.verifiedOnly) out += "filter:verified"
        if (state.followingOnly) out += "filter:follows"

        val recent = positiveWholeNumber(state.withinTimeValue)
        val hasTimeConflict = recent != null && (state.since.isNotBlank() || state.until.isNotBlank())
        if (recent != null) {
            val unit = state.withinTimeUnit.takeIf { it in setOf("d", "h", "m", "s") } ?: "d"
            out += "within_time:$recent$unit"
        } else {
            validDate(state.since)?.let { out += "since:$it" }
            validDate(state.until)?.let { out += "until:$it" }
        }

        nonNegativeWholeNumber(state.minFaves)?.let { out += "min_faves:$it" }
        nonNegativeWholeNumber(state.minRetweets)?.let { out += "min_retweets:$it" }
        nonNegativeWholeNumber(state.minReplies)?.let { out += "min_replies:$it" }

        if (state.filterMedia) out += "filter:media"
        if (state.filterImages) out += "filter:images"
        if (state.filterVideos) out += "filter:videos"
        if (state.filterLinks) out += "filter:links"
        if (state.excludeReplies) out += "-filter:replies"

        state.language.takeIf(String::isNotBlank)?.let { out += "lang:$it" }
        state.near.trim().takeIf(String::isNotEmpty)?.let { near ->
            pushOperator(out, "near:", near)
            positiveWholeNumber(state.withinValue)?.let { distance ->
                val unit = if (state.withinUnit == "mi") "mi" else "km"
                out += "within:$distance$unit"
            }
        }

        pushOperator(out, "url:", state.url.trim())
        if (state.filterQuote) out += "filter:quote"

        return XSearchBuildResult(out.joinToString(" ").trim(), hasTimeConflict)
    }

    fun resultUrl(query: String): String =
        "https://x.com/search?q=${Uri.encode(query.trim())}&src=typed_query"

    private fun splitCsv(value: String): List<String> = value
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun quoteIfNeeded(value: String): String {
        val text = value.trim()
        if (text.startsWith('"') && text.endsWith('"')) return text
        return if (text.any(Char::isWhitespace)) {
            "\"${text.replace("\"", "\\\"")}\""
        } else {
            text
        }
    }

    private fun pushOperator(out: MutableList<String>, operator: String, value: String) {
        value.trim().takeIf(String::isNotEmpty)?.let { out += operator + quoteIfNeeded(it) }
    }

    private fun positiveWholeNumber(value: String): Long? =
        value.trim().toLongOrNull()?.takeIf { it > 0 }

    private fun nonNegativeWholeNumber(value: String): Long? =
        value.trim().toLongOrNull()?.takeIf { it >= 0 }

    private fun validDate(value: String): String? = value.trim().takeIf {
        it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
    }
}

data class XSearchHistoryItem(val query: String, val timestamp: Long)

data class XSearchFavorite(
    val id: String,
    val name: String,
    val query: String,
    val timestamp: Long,
)

/** Small local store mirroring the userscript's history/favorites behavior. */
class XSearchStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(readHistory())
    val history: StateFlow<List<XSearchHistoryItem>> = _history
    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<List<XSearchFavorite>> = _favorites

    @Synchronized
    fun record(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty() || _history.value.firstOrNull()?.query == normalized) return
        _history.value = (listOf(XSearchHistoryItem(normalized, System.currentTimeMillis())) + _history.value)
            .take(HISTORY_MAX)
        writeHistory(_history.value)
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        writeHistory(emptyList())
    }

    @Synchronized
    fun addFavorite(name: String, query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()
        val item = XSearchFavorite(
            id = "fav_${now.toString(36)}_${_favorites.value.size.toString(36)}",
            name = name.trim().ifEmpty { normalized }.take(80),
            query = normalized,
            timestamp = now,
        )
        _favorites.value = (listOf(item) + _favorites.value).take(FAVORITES_MAX)
        writeFavorites(_favorites.value)
    }

    @Synchronized
    fun removeFavorite(id: String) {
        _favorites.value = _favorites.value.filterNot { it.id == id }
        writeFavorites(_favorites.value)
    }

    private fun readHistory(): List<XSearchHistoryItem> = readArray(KEY_HISTORY).mapNotNull { json ->
        json.optString("query").takeIf(String::isNotBlank)?.let {
            XSearchHistoryItem(it, json.optLong("timestamp"))
        }
    }.take(HISTORY_MAX)

    private fun readFavorites(): List<XSearchFavorite> = readArray(KEY_FAVORITES).mapNotNull { json ->
        val id = json.optString("id")
        val query = json.optString("query")
        if (id.isBlank() || query.isBlank()) null else XSearchFavorite(
            id = id,
            name = json.optString("name").ifBlank { query },
            query = query,
            timestamp = json.optLong("timestamp"),
        )
    }.take(FAVORITES_MAX)

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun writeHistory(items: List<XSearchHistoryItem>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("query", it.query).put("timestamp", it.timestamp)) }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun writeFavorites(items: List<XSearchFavorite>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("query", it.query)
                    .put("timestamp", it.timestamp)
            )
        }
        preferences.edit { putString(KEY_FAVORITES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "xverse_search"
        const val KEY_HISTORY = "history"
        const val KEY_FAVORITES = "favorites"
        const val HISTORY_MAX = 50
        const val FAVORITES_MAX = 200
    }
}

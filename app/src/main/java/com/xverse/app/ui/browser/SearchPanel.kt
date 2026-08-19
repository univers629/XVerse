package com.xverse.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.xverse.app.core.search.XSearchFavorite
import com.xverse.app.core.search.XSearchFilterState
import com.xverse.app.core.search.XSearchHistoryItem
import com.xverse.app.core.search.XSearchQuery
import com.xverse.app.ui.common.SmoothDropdownContent

private enum class SearchPanelPage { FILTERS, HISTORY, FAVORITES }
private enum class SearchCategory { QUERY, SOURCE, FILTERS, TYPE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun XSearchPanel(
    state: XSearchFilterState,
    history: List<XSearchHistoryItem>,
    favorites: List<XSearchFavorite>,
    onStateChange: (XSearchFilterState) -> Unit,
    onSearch: (String) -> Unit,
    onSaveFavorite: (String, String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SearchPanelPage.FILTERS) }
    var category by remember { mutableStateOf(SearchCategory.QUERY) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteName by remember { mutableStateOf("") }
    val built = XSearchQuery.build(state)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val panelHeight = with(density) {
        (LocalWindowInfo.current.containerSize.height * 0.66f).toDp()
    }.coerceIn(360.dp, 620.dp)

    fun submit(query: String = built.query) {
        if (query.isBlank()) return
        focusManager.clearFocus()
        keyboard?.hide()
        onSearch(query)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = panelHeight),
        shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Column {
            SearchPanelHeader(
                page = page,
                onPageChange = { page = it },
                onClose = onClose,
            )

            when (page) {
                SearchPanelPage.FILTERS -> {
                    OutlinedTextField(
                        value = state.keywords,
                        onValueChange = { onStateChange(state.copy(keywords = it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        label = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_content)) },
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_placeholder_content)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                    )
                    SearchCategoryTabs(category, onSelected = { category = it })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SearchFields(
                        category = category,
                        state = state,
                        onStateChange = onStateChange,
                        modifier = Modifier.weight(1f),
                    )
                    SearchPanelFooter(
                        query = built.query,
                        hasTimeConflict = built.hasTimeConflict,
                        onReset = { onStateChange(XSearchFilterState()) },
                        onFavorite = {
                            if (built.query.isNotBlank()) {
                                favoriteName = built.query.take(40)
                                showFavoriteDialog = true
                            }
                        },
                        onSearch = { submit() },
                    )
                }

                SearchPanelPage.HISTORY -> SearchHistoryView(
                    history = history,
                    onUse = {
                        onStateChange(XSearchFilterState(keywords = it))
                        page = SearchPanelPage.FILTERS
                    },
                    onSearch = ::submit,
                    onClear = onClearHistory,
                    modifier = Modifier.weight(1f),
                )

                SearchPanelPage.FAVORITES -> SearchFavoritesView(
                    favorites = favorites,
                    onUse = {
                        onStateChange(XSearchFilterState(keywords = it))
                        page = SearchPanelPage.FILTERS
                    },
                    onSearch = ::submit,
                    onRemove = onRemoveFavorite,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showFavoriteDialog) {
        com.xverse.app.ui.common.AppAlertDialog(
            onDismissRequest = { showFavoriteDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
            title = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_fav_title)) },
            text = {
                OutlinedTextField(
                    value = favoriteName,
                    onValueChange = { favoriteName = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_fav_name_label)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSaveFavorite(favoriteName, built.query)
                    showFavoriteDialog = false
                }) {
                    Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFavoriteDialog = false }) { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SearchPanelHeader(
    page: SearchPanelPage,
    onPageChange: (SearchPanelPage) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 10.dp, end = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = when (page) {
                    SearchPanelPage.FILTERS -> androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_tab_advanced)
                    SearchPanelPage.HISTORY -> androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_tab_history)
                    SearchPanelPage.FAVORITES -> androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_tab_favorites)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (page == SearchPanelPage.FILTERS) {
                Text(
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_subtitle_combine),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ExpressivePanelIcon(
            selected = page == SearchPanelPage.HISTORY,
            onClick = {
                onPageChange(if (page == SearchPanelPage.HISTORY) SearchPanelPage.FILTERS else SearchPanelPage.HISTORY)
            },
            icon = { Icon(Icons.Filled.History, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_history)) },
        )
        ExpressivePanelIcon(
            selected = page == SearchPanelPage.FAVORITES,
            onClick = {
                onPageChange(if (page == SearchPanelPage.FAVORITES) SearchPanelPage.FILTERS else SearchPanelPage.FAVORITES)
            },
            icon = { Icon(Icons.Filled.Star, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_favorites)) },
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_close))
        }
    }
}

@Composable
private fun ExpressivePanelIcon(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    if (selected) FilledTonalIconButton(onClick = onClick, content = icon)
    else IconButton(onClick = onClick, content = icon)
}

@Composable
private fun SearchCategoryTabs(selected: SearchCategory, onSelected: (SearchCategory) -> Unit) {
    val categories = listOf(
        Triple(SearchCategory.QUERY, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_cat_query), Icons.Filled.Search),
        Triple(SearchCategory.SOURCE, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_cat_source), Icons.Filled.Person),
        Triple(SearchCategory.FILTERS, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_cat_filters), Icons.Filled.Tune),
        Triple(SearchCategory.TYPE, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_cat_type), Icons.Filled.GridView),
    )
    PrimaryTabRow(
        selectedTabIndex = categories.indexOfFirst { it.first == selected },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        categories.forEach { (category, label, icon) ->
            Tab(
                selected = selected == category,
                onClick = { onSelected(category) },
                text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFields(
    category: SearchCategory,
    state: XSearchFilterState,
    onStateChange: (XSearchFilterState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(category) { scrollState.scrollTo(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (category) {
            SearchCategory.QUERY -> {
                SearchTextField(
                    state.exactPhrase,
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_exact),
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_hint_exact),
                ) {
                    onStateChange(state.copy(exactPhrase = it))
                }
                SearchTextField(
                    state.orTerms,
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_or),
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_hint_or),
                ) {
                    onStateChange(state.copy(orTerms = it))
                }
                SearchTextField(
                    state.exclude,
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_exclude),
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_hint_exclude),
                ) {
                    onStateChange(state.copy(exclude = it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchTextField(
                        value = state.hashtag,
                        label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_hashtag),
                        placeholder = "#AI",
                        modifier = Modifier.weight(1f),
                    ) { onStateChange(state.copy(hashtag = it)) }
                    SearchTextField(
                        value = state.url,
                        label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_domain),
                        placeholder = "youtube.com",
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    ) { onStateChange(state.copy(url = it)) }
                }
                LanguageSelector(state.language) { onStateChange(state.copy(language = it)) }
            }

            SearchCategory.SOURCE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchTextField(
                        state.from,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_from_user),
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_username),
                        Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    ) { onStateChange(state.copy(from = it)) }
                    SearchTextField(
                        state.to,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_to_user),
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_username),
                        Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Filled.People, contentDescription = null) },
                    ) { onStateChange(state.copy(to = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchTextField(
                        state.near,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_place),
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_hint_place),
                        Modifier.weight(1.35f),
                        leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                    ) { onStateChange(state.copy(near = it)) }
                    SearchTextField(
                        state.withinValue,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_radius),
                        "10",
                        Modifier.weight(0.65f),
                        keyboardType = KeyboardType.Number,
                    ) { onStateChange(state.copy(withinValue = it)) }
                    CompactSelector(
                        value = state.withinUnit,
                        options = listOf("km" to "km", "mi" to "mi"),
                        onSelected = { onStateChange(state.copy(withinUnit = it)) },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchToggleChip(
                        selected = state.verifiedOnly,
                        label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_toggle_verified_only),
                        icon = { Icon(Icons.Filled.Verified, contentDescription = null) },
                    ) { onStateChange(state.copy(verifiedOnly = !state.verifiedOnly)) }
                    SearchToggleChip(
                        selected = state.followingOnly,
                        label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_toggle_following_only),
                        icon = { Icon(Icons.Filled.People, contentDescription = null) },
                    ) { onStateChange(state.copy(followingOnly = !state.followingOnly)) }
                }
            }

            SearchCategory.FILTERS -> {
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_sec_recent_time), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchTextField(
                        state.withinTimeValue,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_recent_value),
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_hint_recent_value),
                        Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                    ) { onStateChange(state.copy(withinTimeValue = it)) }
                    CompactSelector(
                        value = state.withinTimeUnit,
                        options = listOf(
                            "d" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_unit_day),
                            "h" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_unit_hour),
                            "m" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_unit_minute),
                            "s" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_unit_second),
                        ),
                        onSelected = { onStateChange(state.copy(withinTimeUnit = it)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchTextField(
                        state.since,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_start_date),
                        "YYYY-MM-DD",
                        Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    ) { onStateChange(state.copy(since = it)) }
                    SearchTextField(
                        state.until,
                        androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_end_date),
                        "YYYY-MM-DD",
                        Modifier.weight(1f),
                        keyboardType = KeyboardType.Number,
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                    ) { onStateChange(state.copy(until = it)) }
                }
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_sec_min_engagement), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchTextField(
                        state.minFaves, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_faves), "0", Modifier.weight(1f), keyboardType = KeyboardType.Number
                    ) { onStateChange(state.copy(minFaves = it)) }
                    SearchTextField(
                        state.minRetweets, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_retweets), "0", Modifier.weight(1f), keyboardType = KeyboardType.Number
                    ) { onStateChange(state.copy(minRetweets = it)) }
                    SearchTextField(
                        state.minReplies, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_replies), "0", Modifier.weight(1f), keyboardType = KeyboardType.Number
                    ) { onStateChange(state.copy(minReplies = it)) }
                }
            }

            SearchCategory.TYPE -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SearchToggleChip(state.filterMedia, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_any_media), { Icon(Icons.Filled.FilterAlt, null) }) {
                        onStateChange(state.copy(filterMedia = !state.filterMedia))
                    }
                    SearchToggleChip(state.filterImages, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_images), { Icon(Icons.Filled.Image, null) }) {
                        onStateChange(state.copy(filterImages = !state.filterImages))
                    }
                    SearchToggleChip(state.filterVideos, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_videos), { Icon(Icons.Filled.Movie, null) }) {
                        onStateChange(state.copy(filterVideos = !state.filterVideos))
                    }
                    SearchToggleChip(state.filterLinks, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_links), { Icon(Icons.Filled.Link, null) }) {
                        onStateChange(state.copy(filterLinks = !state.filterLinks))
                    }
                    SearchToggleChip(state.filterQuote, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_quotes), { Icon(Icons.Filled.People, null) }) {
                        onStateChange(state.copy(filterQuote = !state.filterQuote))
                    }
                    SearchToggleChip(state.excludeReplies, androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_chip_exclude_replies), { Icon(Icons.Filled.FilterAlt, null) }) {
                        onStateChange(state.copy(excludeReplies = !state.excludeReplies))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTextField(
    value: String,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leadingIcon,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun SearchToggleChip(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else icon,
        shape = MaterialTheme.shapes.large,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(value: String, onSelected: (String) -> Unit) {
    val anyLang = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_lang_any)
    val options = listOf(
        "" to anyLang,
        "en" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_en),
        "zh" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_zh),
        "ja" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_ja),
        "ko" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_ko),
        "es" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_es),
        "fr" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_fr),
        "de" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_de),
        "ru" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_ru),
        "pt" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_pt),
        "ar" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_ar),
        "hi" to androidx.compose.ui.res.stringResource(com.xverse.app.R.string.lang_hi),
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: anyLang,
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            label = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_field_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config,
            ) {
                SmoothDropdownContent(expanded = expanded) {
                    options.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelected(code)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSelector(
    value: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: value,
            onValueChange = {},
            modifier = Modifier
                .size(width = 104.dp, height = 64.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config,
            ) {
                SmoothDropdownContent(expanded = expanded) {
                    options.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onSelected(key)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanelFooter(
    query: String,
    hasTimeConflict: Boolean,
    onReset: () -> Unit,
    onFavorite: () -> Unit,
    onSearch: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasTimeConflict) {
            Text(
                androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_tip_recent_override),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = query.ifBlank { androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_preview_placeholder) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_reset), modifier = Modifier.padding(start = 6.dp))
            }
            IconButton(onClick = onFavorite, enabled = query.isNotBlank()) {
                Icon(Icons.Outlined.StarOutline, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_fav_current))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSearch, enabled = query.isNotBlank(), shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_search), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SearchHistoryView(
    history: List<XSearchHistoryItem>,
    onUse: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_clear), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (history.isEmpty()) {
            EmptySearchList(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_empty_history), Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { "${it.timestamp}:${it.query}" }) { item ->
                    SearchListItem(
                        title = item.query,
                        subtitle = null,
                        onUse = { onUse(item.query) },
                        onSearch = { onSearch(item.query) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchFavoritesView(
    favorites: List<XSearchFavorite>,
    onUse: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) {
        EmptySearchList(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_empty_favorites), modifier)
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(favorites, key = XSearchFavorite::id) { item ->
                SearchListItem(
                    title = item.name,
                    subtitle = item.query,
                    onUse = { onUse(item.query) },
                    onSearch = { onSearch(item.query) },
                    onRemove = { onRemove(item.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchListItem(
    title: String,
    subtitle: String?,
    onUse: () -> Unit,
    onSearch: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onUse) { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_use)) }
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_search_now)) }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Filled.DeleteOutline, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.search_icon_remove)) }
            }
        }
    }
}

@Composable
private fun EmptySearchList(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

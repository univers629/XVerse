package com.xverse.app.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XSearchQueryTest {

    @Test
    fun buildsCombinedQueryInStableOrder() {
        val result = XSearchQuery.build(
            XSearchFilterState(
                keywords = "android",
                exactPhrase = "material you",
                orTerms = "compose, kotlin multiplatform",
                exclude = "giveaway, sponsored post",
                hashtag = "#AndroidDev",
                from = "@AndroidDev",
                verifiedOnly = true,
                minFaves = "10",
                filterImages = true,
                excludeReplies = true,
            ),
        )

        assertEquals(
            "android \"material you\" (compose OR \"kotlin multiplatform\") " +
                "-giveaway -\"sponsored post\" #AndroidDev from:AndroidDev " +
                "filter:verified min_faves:10 filter:images -filter:replies",
            result.query,
        )
        assertFalse(result.hasTimeConflict)
    }

    @Test
    fun recentTimeTakesPriorityOverDateRangeAndReportsConflict() {
        val result = XSearchQuery.build(
            XSearchFilterState(
                keywords = "compose",
                withinTimeValue = "7",
                withinTimeUnit = "d",
                since = "2026-01-01",
                until = "2026-02-01",
            ),
        )

        assertEquals("compose within_time:7d", result.query)
        assertTrue(result.hasTimeConflict)
    }

    @Test
    fun ignoresInvalidNumericAndDateValues() {
        val result = XSearchQuery.build(
            XSearchFilterState(
                keywords = "xverse",
                withinTimeValue = "0",
                since = "2026-2-1",
                minFaves = "-1",
                minRetweets = "many",
            ),
        )

        assertEquals("xverse", result.query)
        assertFalse(result.hasTimeConflict)
    }
}

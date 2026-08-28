package com.xverse.app.core.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * syndication tweet-result 取流的纯逻辑用例。
 *
 * token 必须和 x.com 前端的 `((id / 1e15) * Math.PI).toString(36)` 逐字符一致，
 * 期望值取自 Node（V8）实测输出，因此这里同时锁住 base36 的定点算法本身。
 */
class MediaParserSyndicationTest {

    @Test
    fun `jsRadix36 matches V8 toString(36)`() {
        assertEquals("0.000000006dq1a2xwd93", radix36Of("20"))
        assertEquals("0.000000000bhi2ay3f28n", radix36Of("1"))
        assertEquals("2f9.lc2ug9mm", radix36Of("999999999999999999"))
        assertEquals("4aj.esvh5936", radix36Of("1770888775830262034"))
        assertEquals("52l.3rjq1ucd", radix36Of("2092284173730144732"))
        assertEquals("52m.8xeffwk8", radix36Of("2092648130856571283"))
        assertEquals("2zq.ic77uqyk", radix36Of("1234567890123456789"))
    }

    @Test
    fun `syndicationToken strips zeros and dot`() {
        assertEquals("6dq1a2xwd93", MediaParser.syndicationToken("20"))
        assertEquals("bhi2ay3f28n", MediaParser.syndicationToken("1"))
        assertEquals("2f9lc2ug9mm", MediaParser.syndicationToken("999999999999999999"))
        assertEquals("4ajesvh5936", MediaParser.syndicationToken("1770888775830262034"))
        assertEquals("52m8xeffwk8", MediaParser.syndicationToken("2092648130856571283"))
    }

    @Test
    fun `syndicationToken falls back for non numeric id`() {
        assertEquals("x", MediaParser.syndicationToken("not-an-id"))
    }

    @Test
    fun `variantUrl accepts both url and src keys`() {
        assertEquals("https://a/1.mp4", MediaParser.variantUrl(JSONObject("""{"url":"https://a/1.mp4"}"""))) 
        assertEquals("https://a/2.mp4", MediaParser.variantUrl(JSONObject("""{"src":"https://a/2.mp4"}""")))
    }

    @Test
    fun `pixelsOf reads resolution segment`() {
        assertEquals(
            3840L * 2160L,
            MediaParser.pixelsOf("https://video.twimg.com/amplify_video/1/vid/avc1/3840x2160/a.mp4"),
        )
        assertEquals(0L, MediaParser.pixelsOf("https://video.twimg.com/amplify_video/1/pl/a.m3u8"))
    }

    private fun radix36Of(tweetId: String): String =
        MediaParser.jsRadix36(tweetId.toDouble() / 1e15 * Math.PI)
}

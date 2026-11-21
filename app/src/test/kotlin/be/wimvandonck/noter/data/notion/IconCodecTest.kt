package be.wimvandonck.noter.data.notion

import be.wimvandonck.noter.data.model.CustomEmoji
import be.wimvandonck.noter.data.model.PageIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconCodecTest {
    private fun parse(json: String) = IconCodec.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `reads every kind of icon`() {
        assertEquals(PageIcon(emoji = "🥑"), parse("""{"type":"emoji","emoji":"🥑"}"""))
        assertEquals(PageIcon(name = "pizza", color = "blue"), parse("""{"type":"icon","icon":{"name":"pizza","color":"blue"}}"""))
        assertEquals(PageIcon(name = "pizza", color = "gray"), parse("""{"type":"icon","icon":{"name":"pizza"}}"""))
        assertEquals(PageIcon(url = "https://x/a.png"), parse("""{"type":"external","external":{"url":"https://x/a.png"}}"""))
        assertEquals(PageIcon(url = "https://x/bufo.png", customEmojiId = "1"), parse("""{"type":"custom_emoji","custom_emoji":{"id":"1","name":"bufo","url":"https://x/bufo.png"}}"""))
        assertEquals(
            PageIcon(url = "https://s3/f.png", urlExpiry = "2026-10-17T11:00:00.000Z"),
            parse("""{"type":"file","file":{"url":"https://s3/f.png","expiry_time":"2026-10-17T11:00:00.000Z"}}"""),
        )
    }

    @Test
    fun `a custom emoji of the list is read, one without an ID or image is skipped`() {
        fun emoji(json: String) = IconCodec.parseCustomEmoji(Json.parseToJsonElement(json).jsonObject)
        assertEquals(CustomEmoji("45ce", "bufo", "https://x/b.png"), emoji("""{"id":"45ce","name":"bufo","url":"https://x/b.png"}"""))
        assertEquals(CustomEmoji("45ce", "", "https://x/b.png"), emoji("""{"id":"45ce","url":"https://x/b.png"}"""))
        assertNull(emoji("""{"name":"bufo","url":"https://x/b.png"}"""))
        assertNull(emoji("""{"id":"45ce","name":"bufo"}"""))
    }

    @Test
    fun `an icon is sent as an emoji or a custom emoji's ID, and removed as null`() {
        assertEquals(mapOf("type" to "emoji", "emoji" to "🥑"), IconCodec.request("🥑", null))
        assertEquals(
            mapOf("type" to "custom_emoji", "custom_emoji" to mapOf("id" to "45ce")),
            IconCodec.request(null, "45ce"),
        )
        assertNull(IconCodec.request(null, null))
    }

    @Test
    fun `the icon shown for a choice`() {
        assertEquals(PageIcon(emoji = "🥑"), IconCodec.choice("🥑", null, null))
        assertEquals(PageIcon(url = "https://x/b.png", customEmojiId = "45ce"), IconCodec.choice(null, "45ce", "https://x/b.png"))
        assertNull(IconCodec.choice(null, null, null))
    }

    @Test
    fun `no icon or an unknown kind is no icon`() {
        assertNull(IconCodec.parse(null))
        assertNull(parse("""{"type":"something_new","something_new":{}}"""))
    }
}

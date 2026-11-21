package be.wimvandonck.noter.data.sync

import be.wimvandonck.noter.data.model.NoteSummary
import be.wimvandonck.noter.data.model.PageIcon
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxIconTest {
    @Test
    fun `a newer icon replaces the older one, and a refresh keeps it`() {
        var queue = Outbox.add(emptyList(), PendingOp.SetIcon("p", "🥑"))
        queue = Outbox.add(queue, PendingOp.SetIcon("p", null))
        assertEquals(listOf(PendingOp.SetIcon("p", null)), queue)
        val fromNotion = NoteSummary("p", "db", "", icon = PageIcon(emoji = "🥑"))
        assertEquals(null, Outbox.applyPending(fromNotion, queue).icon)
        assertEquals(PageIcon(emoji = "🍕"), Outbox.applyPending(fromNotion, listOf(PendingOp.SetIcon("p", "🍕"))).icon)
    }

    @Test
    fun `a note made on this phone sends its icon with the create`() {
        val local = NoteSummary.LOCAL_PREFIX + "1"
        assertEquals(emptyList<PendingOp>(), Outbox.add(emptyList(), PendingOp.SetIcon(local, "🥑")))
        assertEquals(listOf(PendingOp.SetIcon("new", "🥑")), Outbox.rename(listOf(PendingOp.SetIcon(local, "🥑")), local, "new"))
    }

    @Test
    fun `a custom emoji replaces an emoji, shows at once and keeps its ID through the rename`() {
        val custom = PendingOp.SetIcon("p", null, "45ce", "https://x/b.png")
        val queue = Outbox.add(Outbox.add(emptyList(), PendingOp.SetIcon("p", "🥑")), custom)
        assertEquals(listOf(custom), queue)
        val fromNotion = NoteSummary("p", "db", "", icon = PageIcon(emoji = "🥑"))
        assertEquals(PageIcon(url = "https://x/b.png", customEmojiId = "45ce"), Outbox.applyPending(fromNotion, queue).icon)
        assertEquals(
            listOf(PendingOp.SetIcon("new", null, "45ce", "https://x/b.png")),
            Outbox.rename(listOf(PendingOp.SetIcon(NoteSummary.LOCAL_PREFIX + "1", null, "45ce", "https://x/b.png")), NoteSummary.LOCAL_PREFIX + "1", "new"),
        )
    }

    @Test
    fun `an icon change queued before custom emojis existed still reads`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }
        val old = """[{"kind":"icon","pageId":"p","emoji":"🥑"},{"kind":"icon","pageId":"q","emoji":null}]"""
        assertEquals(
            listOf(PendingOp.SetIcon("p", "🥑"), PendingOp.SetIcon("q", null)),
            json.decodeFromString(ListSerializer(PendingOp.serializer()), old),
        )
        val custom = listOf<PendingOp>(PendingOp.SetIcon("p", null, "45ce", "https://x/b.png"))
        assertEquals(custom, json.decodeFromString(ListSerializer(PendingOp.serializer()), json.encodeToString(ListSerializer(PendingOp.serializer()), custom)))
    }
}

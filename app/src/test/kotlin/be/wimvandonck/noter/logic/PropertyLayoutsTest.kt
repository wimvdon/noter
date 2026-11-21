package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.CardLayout
import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.PropertyLayout
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertySchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyLayoutsTest {
    private fun prop(name: String, type: String = "rich_text") = NoteProperty(name, type, PropertyValue.Text(name))

    private val properties = listOf(prop("Name", "title"), prop("A"), prop("B"), prop("C"), prop("D"), prop("E"))
    private val names = listOf("A", "B", "C", "D", "E")

    @Test
    fun `shows every property by default, as Notion does`() {
        val arranged = PropertyLayouts.arrange(properties, null)
        assertEquals(names, arranged.shown.map { it.name })
        assertEquals(emptyList<String>(), arranged.hidden.map { it.name })
    }

    @Test
    fun `follows the user's order, new properties last and shown`() {
        val layout = PropertyLayout(order = listOf("E", "C", "Gone"), hidden = listOf("C"))
        assertEquals(listOf("E", "C", "A", "B", "D"), PropertyLayouts.ordered(names, layout))
        assertEquals(listOf("E", "A", "B", "D"), PropertyLayouts.arrange(properties, layout).shown.map { it.name })
        assertEquals(listOf("C"), PropertyLayouts.arrange(properties, layout).hidden.map { it.name })
    }

    @Test
    fun `arranging keeps the order of both sections`() {
        val layout = PropertyLayouts.arranged(null, shown = listOf("D", "A"), hidden = listOf("E", "B", "C"))
        assertEquals(PropertyLayouts.sections(names, layout), listOf("D", "A") to listOf("E", "B", "C"))
    }

    @Test
    fun `hiding a property puts it on top of the hidden ones, showing it at the end of the shown ones`() {
        val hidden = PropertyLayouts.toggleShown(null, names, "B")
        assertEquals(listOf("A", "C", "D", "E") to listOf("B"), PropertyLayouts.sections(names, hidden))
        val shown = PropertyLayouts.toggleShown(hidden, names, "B")
        assertEquals(listOf("A", "C", "D", "E", "B") to emptyList<String>(), PropertyLayouts.sections(names, shown))
    }

    @Test
    fun `a card shows at most three properties, in order`() {
        var cards = CardLayout()
        listOf("D", "A", "B", "C").forEach { cards = PropertyLayouts.toggleOnCard(cards, it) }
        assertEquals(listOf("D", "A", "B"), cards.properties)
        val layout = PropertyLayout(onCard = cards.properties)
        assertEquals(listOf("A", "B", "D"), PropertyLayouts.cardProperties(properties, layout).map { it.name })
        cards = PropertyLayouts.toggleOnCard(cards, "A")
        assertEquals(listOf("D", "B"), cards.properties)
    }

    @Test
    fun `cards show the content by default, the header image only when chosen`() {
        assertTrue(PropertyLayouts.showsContent(null))
        assertFalse(PropertyLayouts.showsCover(null, "https://img"))

        val layout = PropertyLayout(coverOnCard = true, contentOnCard = false)
        assertFalse(PropertyLayouts.showsContent(layout))
        assertTrue(PropertyLayouts.showsCover(layout, "https://img"))
        // A note without a header image has nothing to show.
        assertFalse(PropertyLayouts.showsCover(layout, null))
    }

    @Test
    fun `a database's setup chooses its cards, starting from what its cards showed before`() {
        val legacy = PropertyLayout(order = listOf("B", "A"), onCard = listOf("A"), coverOnCard = true)
        val chosen = DatabaseSetup(cards = CardLayout(content = false, properties = listOf("B")))
        assertEquals(CardLayout(cover = true, content = true, properties = listOf("A")), PropertyLayouts.cards(DatabaseSetup(), legacy))
        assertEquals(CardLayout(cover = true, content = true, properties = listOf("A")), PropertyLayouts.cards(null, legacy))
        assertEquals(chosen.cards, PropertyLayouts.cards(chosen, legacy))

        val shown = PropertyLayouts.cardLayouts(mapOf("db" to legacy, "other" to legacy), mapOf("db" to chosen)).getValue("db")
        assertEquals(listOf("B"), shown.onCard)
        assertFalse(shown.contentOnCard)
        assertEquals(listOf("B", "A"), shown.order)
        assertEquals(listOf("A"), PropertyLayouts.cardLayouts(mapOf("other" to legacy), mapOf("db" to chosen)).getValue("other").onCard)
        // A database that has no layout yet.
        assertEquals(listOf("B"), PropertyLayouts.cardLayouts(emptyMap(), mapOf("db" to chosen)).getValue("db").onCard)
    }

    private val schema = DatabaseSchema(
        "db", "Tasks",
        listOf(
            PropertySchema("title", "Name", "title"),
            PropertySchema("a", "A", "status"),
            PropertySchema("b", "B", "date"),
            PropertySchema("c", "C", "people"),
            PropertySchema("d", "D", "select"),
            PropertySchema("e", "E", "number"),
        ),
    )
    private fun notionView(properties: List<String>?, cover: String? = null) = NotionView("v", "View", "gallery", "db", visibleProperties = properties, cover = cover)

    @Test
    fun `cards follow the Notion view by default, with its properties in its order and its header image`() {
        val view = notionView(listOf("title", "d", "a", "c", "e"), cover = "page_cover")
        val cards = PropertyLayouts.cards(null, null, view, schema)
        assertEquals(CardLayout(cover = true, content = true, properties = listOf("D", "A", "C")), cards)
        assertEquals(CardLayout(cover = false, content = true, properties = listOf("B")), PropertyLayouts.cards(DatabaseSetup(), null, notionView(listOf("b")), schema))
    }

    @Test
    fun `cards keep the choice made before, unless the user turns Follow Notion on`() {
        val view = notionView(listOf("d"), cover = "page_cover")
        val chosen = DatabaseSetup(cards = CardLayout(properties = listOf("A")))
        assertFalse(PropertyLayouts.followsNotion(chosen, null))
        assertEquals(chosen.cards, PropertyLayouts.cards(chosen, null, view, schema))
        val legacy = PropertyLayout(onCard = listOf("B"))
        assertFalse(PropertyLayouts.followsNotion(null, legacy))
        assertEquals(listOf("B"), PropertyLayouts.cards(null, legacy, view, schema).properties)
        val following = chosen.copy(cardsFollowNotion = true)
        assertTrue(PropertyLayouts.followsNotion(following, legacy))
        assertEquals(listOf("D"), PropertyLayouts.cards(following, legacy, view, schema).properties)
        assertFalse(PropertyLayouts.followsNotion(DatabaseSetup(cardsFollowNotion = false), null))
    }

    @Test
    fun `cards fall back to the earlier ones while the view's configuration is not known`() {
        assertEquals(CardLayout(), PropertyLayouts.cards(null, null, notionView(null), schema))
        assertEquals(CardLayout(), PropertyLayouts.cards(null, null, null, schema))
        assertEquals(CardLayout(), PropertyLayouts.cards(null, null, notionView(listOf("a")), null))
    }

    @Test
    fun `the lists of notes get what the view shows`() {
        val shown = PropertyLayouts.cardLayouts(emptyMap(), emptyMap(), mapOf("db" to notionView(listOf("c", "a"), "page_cover")), mapOf("db" to schema)).getValue("db")
        assertEquals(listOf("C", "A"), shown.onCard)
        assertTrue(shown.coverOnCard)
    }

    @Test
    fun `a note lists its properties as the Notion view does until the user arranged them`() {
        val viewOrder = listOf("D", "B", "Gone", "A")
        assertEquals(listOf("D", "B", "A", "C", "E"), PropertyLayouts.arrange(properties, null, viewOrder).shown.map { it.name })
        // The ones the view hides still show, after the listed ones.
        assertEquals(listOf("E", "C", "A", "B", "D"), PropertyLayouts.arrange(properties, PropertyLayout(order = listOf("E", "C")), viewOrder).shown.map { it.name })
        // Hiding without arranging keeps the view's order.
        assertEquals(listOf("D", "B", "A", "C", "E"), PropertyLayouts.arrange(properties, PropertyLayout(), viewOrder).shown.map { it.name })
        assertEquals(names, PropertyLayouts.arrange(properties, null).shown.map { it.name })
    }
}

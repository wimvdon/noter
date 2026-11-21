package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.CardLayout
import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.DatabaseSetup
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.NotionView
import be.wimvandonck.noter.data.model.PropertyLayout

/**
 * Which of a note's properties are shown, in which order, and which appear on its card
 * ([PropertyLayout], one per database). As in Notion, all of them are shown until the user hides
 * some; hidden ones are folded away. The title is never part of it (it has its own field).
 */
object PropertyLayouts {
    const val MAX_ON_CARD = 3

    /** A note's properties, split into the shown ones and the hidden ones, in the user's order. */
    data class Arranged(val shown: List<NoteProperty>, val hidden: List<NoteProperty>)

    /**
     * [names] in the user's order: the ordered ones first, then the rest as they come. Until the
     * user arranged them, [preferred] (the order of a Notion view) takes the place of their order.
     */
    fun ordered(names: List<String>, layout: PropertyLayout?, preferred: List<String> = emptyList()): List<String> {
        val order = layout?.order.orEmpty().ifEmpty { preferred }
        val first = order.filter { it in names }.distinct()
        return first + names.filterNot { it in first }
    }

    /** [names] in the user's order, split into the shown ones and the hidden ones. */
    fun sections(names: List<String>, layout: PropertyLayout?, preferred: List<String> = emptyList()): Pair<List<String>, List<String>> {
        val hidden = layout?.hidden.orEmpty().toSet()
        return ordered(names, layout, preferred).partition { it !in hidden }
    }

    fun arrange(properties: List<NoteProperty>, layout: PropertyLayout?, preferred: List<String> = emptyList()): Arranged {
        val byName = properties.filter { it.type != "title" }.associateBy { it.name }
        val (shown, hidden) = sections(byName.keys.toList(), layout, preferred)
        return Arranged(shown.mapNotNull { byName[it] }, hidden.mapNotNull { byName[it] })
    }

    /** The layout after the user arranged the properties: [shown] in this order, then [hidden]. */
    fun arranged(layout: PropertyLayout?, shown: List<String>, hidden: List<String>): PropertyLayout =
        (layout ?: PropertyLayout()).copy(order = shown + hidden, hidden = hidden)

    /** Shows or hides [name]: it moves to the end of the shown ones, or the top of the hidden ones. */
    fun toggleShown(layout: PropertyLayout?, names: List<String>, name: String): PropertyLayout {
        val (shown, hidden) = sections(names, layout)
        return if (name in shown) arranged(layout, shown - name, listOf(name) + hidden) else arranged(layout, shown + name, hidden - name)
    }

    /** What a database's cards showed before cards were part of its setup ([layout]). */
    private fun earlierCards(layout: PropertyLayout?): CardLayout =
        CardLayout(layout?.coverOnCard == true, layout?.contentOnCard != false, layout?.onCard.orEmpty())

    /**
     * True when a database's cards show what its Notion view shows: unless the user chose
     * otherwise ([DatabaseSetup.cardsFollowNotion]), they do until cards were chosen for the
     * database, now or in an earlier version (those keep their choice).
     */
    fun followsNotion(setup: DatabaseSetup?, layout: PropertyLayout?): Boolean =
        setup?.cardsFollowNotion ?: (setup?.cards == null && earlierCards(layout) == CardLayout())

    /**
     * What a database's cards show: what its Notion [view] shows ([schema] names its properties)
     * while they follow Notion and the view is known, else the setup's choice ([setup]), else what
     * its cards showed before cards were part of the setup ([layout]). Notion's cards show the
     * start of the content, as the cards here do by default.
     */
    fun cards(setup: DatabaseSetup?, layout: PropertyLayout?, view: NotionView? = null, schema: DatabaseSchema? = null): CardLayout {
        val names = if (followsNotion(setup, layout)) NotionViews.propertyNames(view, schema) else null
        if (names != null) return CardLayout(cover = NotionViews.showsCover(view), content = true, properties = names.take(MAX_ON_CARD))
        return setup?.cards ?: earlierCards(layout)
    }

    /** Puts [name] on the card or takes it off; nothing changes when the card already has [MAX_ON_CARD]. */
    fun toggleOnCard(cards: CardLayout, name: String): CardLayout = when {
        name in cards.properties -> cards.copy(properties = cards.properties - name)
        cards.properties.size >= MAX_ON_CARD -> cards
        else -> cards.copy(properties = cards.properties + name)
    }

    /** Each database's layout with what its cards show filled in from its setup, for the lists of notes. */
    fun cardLayouts(
        layouts: Map<String, PropertyLayout>,
        setups: Map<String, DatabaseSetup>,
        views: Map<String, NotionView> = emptyMap(),
        schemas: Map<String, DatabaseSchema> = emptyMap(),
    ): Map<String, PropertyLayout> =
        (layouts.keys + setups.keys + views.keys).associateWith { source ->
            val cards = cards(setups[source], layouts[source], views[source], schemas[source])
            (layouts[source] ?: PropertyLayout()).copy(coverOnCard = cards.cover, contentOnCard = cards.content, onCard = cards.properties)
        }

    /** True when a card shows its note's header image (only when the note has one). */
    fun showsCover(layout: PropertyLayout?, cover: String?): Boolean = cover != null && layout?.coverOnCard == true

    /** True when a card shows the start of its note's content (so the preview is needed). */
    fun showsContent(layout: PropertyLayout?): Boolean = layout?.contentOnCard != false

    /** The properties shown on a card, in the user's order. */
    fun cardProperties(properties: List<NoteProperty>, layout: PropertyLayout?): List<NoteProperty> {
        val onCard = layout?.onCard.orEmpty()
        if (onCard.isEmpty()) return emptyList()
        val byName = properties.associateBy { it.name }
        return ordered(properties.map { it.name }, layout).filter { it in onCard }.mapNotNull { byName[it] }
    }
}

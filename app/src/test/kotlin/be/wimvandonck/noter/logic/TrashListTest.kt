package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.TrashedPage
import be.wimvandonck.noter.data.notion.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TrashListTest {
    private val work = Workspace("w1", "Work", TokenKind.PERSONAL)
    private val club = Workspace("w2", "Club", TokenKind.CONNECTION)

    @Test
    fun `pages are split by the workspace that answered for them or for where they were`() {
        val pages = listOf(
            TrashedPage("a", "A"),
            TrashedPage("b", "B", parentId = "club-db", parentIsDatabase = true),
            TrashedPage("c", "C"),
        )
        val groups = TrashList.groups(pages, mapOf("a" to "w1", "clubdb" to "w2"), listOf(work, club))
        assertEquals(listOf("Work", "Club", null), groups.map { it.workspace?.name })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), groups.map { g -> g.items.map { it.id } })
    }

    @Test
    fun `a restored page leaves the list, whichever way its ID is written`() {
        val pages = listOf(TrashedPage("1234-5678", "A"), TrashedPage("zzz", "B"))
        assertEquals(listOf("zzz"), TrashList.without(pages, "12345678").map { it.id })
    }
}

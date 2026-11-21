package be.wimvandonck.noter.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxTableRowTest {
    @Test
    fun `a newer edit of a row replaces the older one, other rows stay`() {
        var queue = Outbox.add(emptyList(), PendingOp.SetTableRow("p", "r1", listOf("a")))
        queue = Outbox.add(queue, PendingOp.SetTableRow("p", "r2", listOf("b")))
        queue = Outbox.add(queue, PendingOp.SetTableRow("p", "r1", listOf("c")))
        assertEquals(listOf(PendingOp.SetTableRow("p", "r1", listOf("c")), PendingOp.SetTableRow("p", "r2", listOf("b"))), queue)
    }

    @Test
    fun `a row edit follows its note's new ID`() {
        val queue = listOf(PendingOp.SetTableRow("old", "r1", listOf("a")))
        assertEquals(listOf(PendingOp.SetTableRow("new", "r1", listOf("a"))), Outbox.rename(queue, "old", "new"))
    }
}

package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.BlockType
import be.wimvandonck.noter.data.notion.InlineTokens
import be.wimvandonck.noter.data.sync.PendingOp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableCellsTest {
    private val mention = InlineTokens.atom("""{"type":"mention"}""", "@Ann")
    private val table = Block(
        key = "t",
        notionId = "tbl",
        type = BlockType.UNSUPPORTED,
        rawType = "table",
        hasChildren = true,
        rows = listOf(listOf("Name", "Who"), listOf("**Bold**", mention), listOf("short")),
        rowIds = listOf("r0", "r1", "r2"),
    )

    @Test
    fun `a table with row IDs is editable, an old copy without them is not`() {
        assertTrue(TableCells.editable(table))
        assertFalse(TableCells.editable(table.copy(rowIds = null)))
        assertFalse(TableCells.editable(table.copy(rowIds = listOf("r0"))))
        assertFalse(TableCells.editable(table.copy(rows = null)))
        assertFalse(TableCells.editable(table.copy(rowIds = listOf("r0", "", "r2"))))
        assertTrue(TableCells.needsRowIds(listOf(table.copy(rowIds = null))))
        assertFalse(TableCells.needsRowIds(listOf(table, Block("p", text = "Hi"))))
    }

    @Test
    fun `an old cached table without row IDs still reads`() {
        val old = """{"key":"t","type":"UNSUPPORTED","rawType":"table","hasChildren":true,"rows":[["a","b"]]}"""
        val block = Json { ignoreUnknownKeys = true }.decodeFromString<Block>(old)
        assertEquals(listOf(listOf("a", "b")), block.rows)
        assertNull(block.rowIds)
    }

    @Test
    fun `editing a cell changes only that cell`() {
        val edited = TableCells.withCell(table, row = 1, column = 0, text = "New")
        assertEquals(listOf("New", mention), edited.rows!![1])
        assertEquals(table.rows.orEmpty()[0], edited.rows!![0])
        assertEquals(table.rows.orEmpty()[2], edited.rows!![2])
    }

    @Test
    fun `a short row is filled up to the table's width`() {
        assertEquals(listOf("short", ""), TableCells.rowCells(table, 2))
        val edited = TableCells.withCell(table, row = 2, column = 1, text = "x")
        assertEquals(listOf("short", "x"), edited.rows!![2])
    }

    @Test
    fun `a cell that isn't there changes nothing`() {
        assertEquals(table, TableCells.withCell(table, 5, 0, "x"))
        assertEquals(table, TableCells.withCell(table, 0, 9, "x"))
    }

    @Test
    fun `waiting row edits are put on a downloaded table`() {
        val queue = listOf(PendingOp.SetTableRow("p", "r0", listOf("Edited", "Who")), PendingOp.SetTableRow("p", "other", listOf("zzz")))
        val applied = TableCells.applyPending(listOf(table), queue)
        assertEquals(listOf("Edited", "Who"), applied.single().rows!![0])
        assertEquals(table.rows.orEmpty()[1], applied.single().rows!![1])
    }
}

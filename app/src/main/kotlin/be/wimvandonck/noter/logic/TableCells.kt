package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.sync.PendingOp

/**
 * Editing the text of a table's cells. Rows and columns can't be added or removed here, so a
 * table keeps its shape; a cell holds inline Markdown like a text line, with mentions and colours
 * kept (InlineTokens), so what isn't edited is sent back to Notion as it was.
 */
object TableCells {
    /**
     * The cells of [block] can be edited: it is a table whose rows are known with their IDs. A
     * copy made before cells could be edited lacks the IDs until the note is downloaded again.
     */
    fun editable(block: Block): Boolean {
        val rows = block.rows ?: return false
        val ids = block.rowIds ?: return false
        return block.rawType == "table" && rows.isNotEmpty() && ids.size == rows.size && ids.none { it.isEmpty() }
    }

    /** True when a table in [blocks] has cells but no row IDs yet (see [editable]): it needs a download. */
    fun needsRowIds(blocks: List<Block>): Boolean =
        blocks.any { it.rawType == "table" && !it.rows.isNullOrEmpty() && !editable(it) }

    /** The cells of row [row], every column of the table (a short row is filled up with empty cells). */
    fun rowCells(block: Block, row: Int): List<String> {
        val rows = block.rows.orEmpty()
        val width = rows.maxOfOrNull { it.size } ?: 0
        val cells = rows.getOrNull(row).orEmpty()
        return List(width) { cells.getOrElse(it) { "" } }
    }

    /** [block] with the cell at [row], [column] set to [text] (Markdown). Unchanged for a cell that isn't there. */
    fun withCell(block: Block, row: Int, column: Int, text: String): Block {
        val rows = block.rows ?: return block
        val cells = rowCells(block, row)
        if (row !in rows.indices || column !in cells.indices) return block
        val edited = cells.toMutableList().apply { set(column, text) }
        return block.copy(rows = rows.mapIndexed { r, old -> if (r == row) edited else old })
    }

    /** [blocks] with the cells of row [rowId] set to [cells]. */
    fun withRow(blocks: List<Block>, rowId: String, cells: List<String>): List<Block> =
        blocks.map { block ->
            val at = block.rowIds?.indexOf(rowId) ?: -1
            if (at < 0 || block.rows == null) block else block.copy(rows = block.rows.mapIndexed { r, old -> if (r == at) cells else old })
        }

    /** [blocks] (as Notion has them) with the row edits still waiting to be sent, so a refresh doesn't undo them. */
    fun applyPending(blocks: List<Block>, queue: List<PendingOp>): List<Block> =
        queue.filterIsInstance<PendingOp.SetTableRow>().fold(blocks) { acc, op -> withRow(acc, op.rowId, op.cells) }
}

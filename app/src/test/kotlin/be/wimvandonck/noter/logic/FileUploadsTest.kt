package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.Block
import be.wimvandonck.noter.data.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUploadsTest {
    private val mib = 1024L * 1024

    @Test
    fun `the limit is the workspace's, never more than one request takes`() {
        assertEquals(5 * mib, FileUploads.limit(5 * mib))
        assertEquals(20 * mib, FileUploads.limit(5L * 1024 * mib))
        assertEquals(20 * mib, FileUploads.limit(null))
        assertEquals(20 * mib, FileUploads.limit(0))
    }

    @Test
    fun `a file fits up to the limit, an empty one doesn't`() {
        assertTrue(FileUploads.fits(5 * mib, 5 * mib))
        assertFalse(FileUploads.fits(5 * mib + 1, 5 * mib))
        assertFalse(FileUploads.fits(0, 5 * mib))
        assertEquals(5, FileUploads.megabytes(5 * mib))
        assertEquals(20, FileUploads.megabytes(FileUploads.SINGLE_PART_MAX_BYTES))
    }

    @Test
    fun `images Notion shows go in an image block, everything else in a file block`() {
        assertEquals("image", FileUploads.blockType("image/jpeg"))
        assertEquals("image", FileUploads.blockType("IMAGE/PNG"))
        assertEquals("file", FileUploads.blockType("image/x-canon-cr2"))
        assertEquals("file", FileUploads.blockType("application/pdf"))
    }

    @Test
    fun `a name without extension gets one for its kind`() {
        assertEquals("IMG_1234.jpg", FileUploads.fileName("IMG_1234", "image/jpeg"))
        assertEquals("report.pdf", FileUploads.fileName("report.pdf", "application/pdf"))
        assertEquals("file.png", FileUploads.fileName(null, "image/png"))
        assertEquals("notes", FileUploads.fileName(" notes ", "application/x-unknown"))
    }

    private val content = listOf(
        Block("a", notionId = "A", text = "One"),
        Block("t", notionId = "T", type = BlockType.TOGGLE, text = "Toggle"),
        Block("c", notionId = "C", text = "Inside", depth = 1, parentId = "T"),
        Block("b", notionId = "B", text = "Two"),
        Block("n", text = "New, not sent"),
    )
    private val image = Block("img", notionId = "I", type = BlockType.UNSUPPORTED, rawType = "image")

    @Test
    fun `the block goes after the line and what is nested in it`() {
        val result = FileUploads.insertAfter(content, "t", image)
        assertEquals(listOf("a", "t", "c", "img", "b", "n"), result.map { it.key })
        assertEquals(0, result[3].depth)
        assertNull(result[3].parentId)
    }

    @Test
    fun `after a nested line it goes into the same parent`() {
        val result = FileUploads.insertAfter(content, "c", image)
        assertEquals(listOf("a", "t", "c", "img", "b", "n"), result.map { it.key })
        assertEquals(1, result[3].depth)
        assertEquals("T", result[3].parentId)
    }

    @Test
    fun `without a line, or when it is gone, at the end`() {
        assertEquals("img", FileUploads.insertAfter(content, null, image).last().key)
        assertEquals("img", FileUploads.insertAfter(content, "gone", image).last().key)
    }

    @Test
    fun `Notion's copy is matched by ID`() {
        val result = FileUploads.insertAfter(content, "A", image, byNotionId = true)
        assertEquals(listOf("a", "img", "t", "c", "b", "n"), result.map { it.key })
    }

    @Test
    fun `where the block goes in Notion`() {
        assertEquals(FileUploads.Target(parentId = null, afterId = "A"), FileUploads.target(content, "a"))
        assertEquals(FileUploads.Target(parentId = "T", afterId = "C"), FileUploads.target(content, "c"))
        assertEquals(FileUploads.Target(parentId = null, afterId = null), FileUploads.target(content, null))
        // A line Notion doesn't have yet can't be pointed at.
        assertNull(FileUploads.target(content, "n"))
    }

    @Test
    fun `a file on its way shows under the last line it will follow`() {
        assertEquals("c", FileUploads.shownAfter(content, "t"))
        assertEquals("a", FileUploads.shownAfter(content, "a"))
        assertNull(FileUploads.shownAfter(content, null))
        assertNull(FileUploads.shownAfter(content, "gone"))
    }
}

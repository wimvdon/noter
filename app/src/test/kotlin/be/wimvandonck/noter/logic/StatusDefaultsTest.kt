package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.SelectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusDefaultsTest {
    private val doing = SelectOption("Doing", "blue", "2")
    private val stage = PropertySchema("s", "Stage", "status", options = listOf(SelectOption("Todo", "gray", "1"), doing), defaultOption = "Doing")
    private val schema = DatabaseSchema("db", "Notes", listOf(PropertySchema("t", "Name", "title"), stage))

    @Test
    fun `the default option comes with its color`() {
        assertEquals(doing, StatusDefaults.option(stage))
        assertEquals(mapOf("Stage" to doing), StatusDefaults.of(schema))
    }

    @Test
    fun `no default, or not a status, gives nothing`() {
        assertNull(StatusDefaults.option(stage.copy(defaultOption = null)))
        assertNull(StatusDefaults.option(stage.copy(type = "select")))
        assertNull(StatusDefaults.option(null))
        assertEquals(emptyMap<String, SelectOption>(), StatusDefaults.of(null))
    }

    @Test
    fun `a default that isn't among the options is still shown by name`() {
        assertEquals(SelectOption("Gone"), StatusDefaults.option(stage.copy(defaultOption = "Gone")))
    }

    @Test
    fun `an empty status shows the default, a chosen one stays`() {
        val empty = NoteProperty("Stage", "status", PropertyValue.Select(null))
        val chosen = NoteProperty("Stage", "status", PropertyValue.Select(SelectOption("Todo", "gray")))
        val defaults = StatusDefaults.of(schema)
        assertEquals(PropertyValue.Select(doing), StatusDefaults.shown(listOf(empty), defaults).single().value)
        assertEquals(chosen, StatusDefaults.shown(listOf(chosen), defaults).single())
    }

    @Test
    fun `an empty select is never filled`() {
        val select = NoteProperty("Stage", "select", PropertyValue.Select(null))
        assertEquals(select, StatusDefaults.shown(listOf(select), StatusDefaults.of(schema)).single())
    }
}

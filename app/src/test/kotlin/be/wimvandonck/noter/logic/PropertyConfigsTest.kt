package be.wimvandonck.noter.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyConfigsTest {
    @Test
    fun `a formula sends its expression, trimmed`() {
        assertEquals(
            mapOf("expression" to "prop(\"Price\") * 2"),
            PropertyConfigs.config(PropertyKind.FORMULA, PropertySettings(formula = "  prop(\"Price\") * 2 ")),
        )
    }

    @Test
    fun `a relation is one-way, or two-way so the other database shows it too`() {
        assertEquals(
            mapOf("data_source_id" to "ds-2", "type" to "single_property", "single_property" to emptyMap<String, Any?>()),
            PropertyConfigs.config(PropertyKind.RELATION, PropertySettings(relationTarget = "ds-2")),
        )
        assertEquals(
            mapOf("data_source_id" to "ds-2", "type" to "dual_property", "dual_property" to emptyMap<String, Any?>()),
            PropertyConfigs.config(PropertyKind.RELATION, PropertySettings(relationTarget = "ds-2", twoWay = true)),
        )
    }

    @Test
    fun `a rollup sends its relation and property by ID and its function`() {
        assertEquals(
            mapOf("relation_property_id" to "r1", "rollup_property_id" to "p9", "function" to "sum"),
            PropertyConfigs.config(PropertyKind.ROLLUP, PropertySettings(rollupRelation = "r1", rollupProperty = "p9", rollupFunction = "sum")),
        )
    }

    @Test
    fun `a unique ID sends its prefix, null without one`() {
        assertEquals(mapOf("prefix" to "TASK"), PropertyConfigs.config(PropertyKind.UNIQUE_ID, PropertySettings(idPrefix = " TASK ")))
        assertEquals(mapOf("prefix" to null), PropertyConfigs.config(PropertyKind.UNIQUE_ID, PropertySettings()))
    }

    @Test
    fun `a number sends its format, place and status nothing`() {
        assertEquals(mapOf("format" to "euro"), PropertyConfigs.config(PropertyKind.NUMBER, PropertySettings(numberFormat = "euro")))
        assertEquals(mapOf("format" to "number"), PropertyConfigs.config(PropertyKind.NUMBER))
        assertEquals(emptyMap<String, Any?>(), PropertyConfigs.config(PropertyKind.PLACE))
        assertEquals(emptyMap<String, Any?>(), PropertyConfigs.config(PropertyKind.STATUS))
    }

    @Test
    fun `formulas, relations and rollups need their settings`() {
        assertFalse(PropertyConfigs.isComplete(PropertyKind.FORMULA, PropertySettings(formula = " ")))
        assertTrue(PropertyConfigs.isComplete(PropertyKind.FORMULA, PropertySettings(formula = "1")))
        assertFalse(PropertyConfigs.isComplete(PropertyKind.RELATION, PropertySettings()))
        assertFalse(PropertyConfigs.isComplete(PropertyKind.ROLLUP, PropertySettings(rollupRelation = "r1")))
        assertTrue(PropertyConfigs.isComplete(PropertyKind.ROLLUP, PropertySettings(rollupRelation = "r1", rollupProperty = "p")))
        assertTrue(PropertyConfigs.isComplete(PropertyKind.UNIQUE_ID, PropertySettings()))
    }

    @Test
    fun `rollup functions follow the related property's type`() {
        assertTrue("sum" in PropertyConfigs.rollupFunctions("number"))
        assertFalse("sum" in PropertyConfigs.rollupFunctions("rich_text"))
        assertTrue("latest_date" in PropertyConfigs.rollupFunctions("date"))
        assertTrue("percent_checked" in PropertyConfigs.rollupFunctions("checkbox"))
        assertEquals("show_original", PropertyConfigs.fitFunction("sum", "rich_text"))
        assertEquals("sum", PropertyConfigs.fitFunction("sum", "number"))
        assertTrue(PropertyConfigs.ROLLUP_FUNCTIONS.containsAll(PropertyConfigs.rollupFunctions("number")))
    }

    @Test
    fun `the title and a place can't change type, nothing becomes a place`() {
        assertTrue(PropertyConfigs.typeChanges("title", hasRelation = true, hasUniqueId = false).isEmpty())
        assertTrue(PropertyConfigs.typeChanges("place", hasRelation = true, hasUniqueId = false).isEmpty())
        val fromText = PropertyConfigs.typeChanges("rich_text", hasRelation = false, hasUniqueId = true)
        assertFalse(PropertyKind.PLACE in fromText)
        assertFalse(PropertyKind.TEXT in fromText)
        assertFalse(PropertyKind.ROLLUP in fromText)
        assertFalse(PropertyKind.UNIQUE_ID in fromText)
        assertTrue(PropertyKind.SELECT in fromText)
        assertTrue(PropertyKind.ROLLUP in PropertyConfigs.typeChanges("rich_text", hasRelation = true, hasUniqueId = false))
    }

    @Test
    fun `Add property offers every kind, a rollup only with a relation and one unique ID`() {
        assertEquals(PropertyKind.entries.toList(), PropertyConfigs.addable(hasRelation = true, hasUniqueId = false))
        assertFalse(PropertyKind.ROLLUP in PropertyConfigs.addable(hasRelation = false, hasUniqueId = false))
        assertFalse(PropertyKind.UNIQUE_ID in PropertyConfigs.addable(hasRelation = true, hasUniqueId = true))
        assertTrue(PropertyKind.STATUS in PropertyConfigs.addable(hasRelation = false, hasUniqueId = false))
    }
}

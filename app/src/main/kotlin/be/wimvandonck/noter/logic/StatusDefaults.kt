package be.wimvandonck.noter.logic

import be.wimvandonck.noter.data.model.DatabaseSchema
import be.wimvandonck.noter.data.model.NoteProperty
import be.wimvandonck.noter.data.model.PropertySchema
import be.wimvandonck.noter.data.model.PropertyValue
import be.wimvandonck.noter.data.model.SelectOption

/**
 * A status property can have a default option: Notion shows it for a note without a status. The
 * app does the same, for display only: nothing is written to the note.
 */
object StatusDefaults {
    /** The default option of a status property (with its colour), or null. */
    fun option(schema: PropertySchema?): SelectOption? {
        if (schema == null || schema.type != "status") return null
        val name = schema.defaultOption?.takeIf { it.isNotBlank() } ?: return null
        return schema.options.firstOrNull { it.name == name } ?: SelectOption(name)
    }

    /** The default options of a database's status properties, by property name. */
    fun of(schema: DatabaseSchema?): Map<String, SelectOption> =
        schema?.properties.orEmpty().mapNotNull { property -> option(property)?.let { property.name to it } }.toMap()

    /** [properties] with the default option in each empty status ([defaults]: by property name). */
    fun shown(properties: List<NoteProperty>, defaults: Map<String, SelectOption>): List<NoteProperty> {
        if (defaults.isEmpty()) return properties
        return properties.map { property ->
            val default = defaults[property.name]
            val value = property.value
            if (default != null && property.type == "status" && value is PropertyValue.Select && value.option == null) {
                property.copy(value = PropertyValue.Select(default))
            } else {
                property
            }
        }
    }
}

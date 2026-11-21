package be.wimvandonck.noter.data.notion

/**
 * The parts of a line's stored text that the user doesn't type: mentions and inline equations
 * (kept whole, as Notion sent them) and Notion's colour or underline around a piece of text.
 *
 * They are stored in the text itself with private-use characters, so a line keeps them through
 * everything that handles its text (the cache, the diff, splitting and joining lines):
 * - an atom (a mention or equation): `ATOM_OPEN json ATOM_MID label ATOM_CLOSE`. Only its label
 *   shows; it is written back to Notion as `json`.
 * - a span (colour, underline): `SPAN_OPEN attrs SPAN_MID text SPAN_CLOSE`. The text in it shows
 *   and can be edited; the markers don't show.
 *
 * The editor shows [display] text and turns each change of it back into stored text with [edit],
 * so an atom is never half edited: deleting any part of it removes the whole atom.
 */
object InlineTokens {
    const val ATOM_OPEN = ''
    const val ATOM_MID = ''
    const val ATOM_CLOSE = ''
    const val SPAN_OPEN = ''
    const val SPAN_MID = ''
    const val SPAN_CLOSE = ''

    fun isMarker(c: Char): Boolean = c in ATOM_OPEN..SPAN_CLOSE

    /** [text] without marker characters (for text from Notion or pasted by the user). */
    fun sanitize(text: String): String = if (text.none(::isMarker)) text else text.filterNot(::isMarker)

    /** The stored form of an atom: [label] shown, [json] sent back to Notion. */
    fun atom(json: String, label: String): String =
        "$ATOM_OPEN${sanitize(json)}$ATOM_MID${sanitize(label).replace('\n', ' ')}$ATOM_CLOSE"

    /** The stored form of a span opening with [color] (null: default) and [underline]. */
    fun spanOpen(color: String?, underline: Boolean): String =
        "$SPAN_OPEN${color.orEmpty()}|${if (underline) "u" else ""}$SPAN_MID"

    /** An atom found in stored text: its JSON, label and where it ends (after ATOM_CLOSE). */
    data class FoundAtom(val json: String, val label: String, val end: Int)

    /** The atom starting at [at] (which holds ATOM_OPEN), or null when it is incomplete. */
    fun atomAt(text: String, at: Int): FoundAtom? {
        if (text.getOrNull(at) != ATOM_OPEN) return null
        val mid = nextMarker(text, at + 1)
        if (mid < 0 || text[mid] != ATOM_MID) return null
        val close = nextMarker(text, mid + 1)
        if (close < 0 || text[close] != ATOM_CLOSE) return null
        return FoundAtom(text.substring(at + 1, mid), text.substring(mid + 1, close), close + 1)
    }

    /** A span opening found in stored text: its colour (null: default), underline and where its text starts. */
    data class FoundSpan(val color: String?, val underline: Boolean, val end: Int)

    /** The span opening at [at] (which holds SPAN_OPEN), or null when it is incomplete. */
    fun spanAt(text: String, at: Int): FoundSpan? {
        if (text.getOrNull(at) != SPAN_OPEN) return null
        val mid = nextMarker(text, at + 1)
        if (mid < 0 || text[mid] != SPAN_MID) return null
        val attrs = text.substring(at + 1, mid).split('|')
        val color = attrs.getOrNull(0)?.takeIf { it.isNotEmpty() && it != "default" }
        return FoundSpan(color, attrs.getOrNull(1) == "u", mid + 1)
    }

    private fun nextMarker(text: String, from: Int): Int {
        for (i in from until text.length) if (isMarker(text[i])) return i
        return -1
    }

    /** True when [text] has atoms or spans (most text has none, and is shown as stored). */
    fun hasTokens(text: String): Boolean = text.any(::isMarker)

    // ---- Stored text <-> what the editor shows ----

    /** An atom in [Layout.display]: its label is at [start] until [end]. */
    data class AtomRange(val start: Int, val end: Int, val storedStart: Int, val storedEnd: Int)

    /** Stored text as the editor shows it, with the way back. */
    class Layout internal constructor(
        val display: String,
        val atoms: List<AtomRange>,
        /** For each display character, the stored index of that character (an atom's: its start). */
        private val stored: IntArray,
        /** For each display character, the atom it belongs to, or -1. */
        private val atomOf: IntArray,
    ) {
        /** The stored index where the display character at [d] starts (with its atom). */
        internal fun startOf(d: Int): Int = atomOf[d].let { if (it >= 0) atoms[it].storedStart else stored[d] }

        /** The stored index right after the display character at [d] (with its atom). */
        internal fun endOf(d: Int): Int = atomOf[d].let { if (it >= 0) atoms[it].storedEnd else stored[d] + 1 }

        internal fun atomAt(d: Int): AtomRange? = atomOf.getOrNull(d)?.takeIf { it >= 0 }?.let { atoms[it] }
    }

    fun layout(text: String): Layout {
        val display = StringBuilder()
        val stored = ArrayList<Int>(text.length)
        val atomOf = ArrayList<Int>(text.length)
        val atoms = mutableListOf<AtomRange>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ATOM_OPEN && atomAt(text, i) != null -> {
                    val atom = atomAt(text, i)!!
                    val start = display.length
                    display.append(atom.label)
                    repeat(atom.label.length) { stored += i; atomOf += atoms.size }
                    atoms += AtomRange(start, display.length, i, atom.end)
                    i = atom.end
                }
                c == SPAN_OPEN && spanAt(text, i) != null -> i = spanAt(text, i)!!.end
                isMarker(c) -> i += 1
                else -> {
                    display.append(c); stored += i; atomOf += -1; i += 1
                }
            }
        }
        return Layout(display.toString(), atoms, stored.toIntArray(), atomOf.toIntArray())
    }

    /** What the editor shows for stored [text]: atoms as their label, no span markers. */
    fun display(text: String): String = if (hasTokens(text)) layout(text).display else text

    /** The length of [display] text, for cursor positions. */
    fun displayLength(text: String): Int = display(text).length

    /** The result of an edit: the new stored text, what the editor shows and where its cursor goes. */
    data class Edit(val stored: String, val display: String, val cursor: Int)

    /**
     * The user changed the shown text of stored [text] into [newDisplay], with the cursor at
     * [cursor]. Deleting or typing over any part of an atom removes the whole atom; typing inside
     * one goes after it. Typed text joins the span of the character before it (as in Notion).
     */
    fun edit(text: String, newDisplay: String, cursor: Int): Edit {
        if (!hasTokens(text)) {
            val clean = sanitize(newDisplay)
            return Edit(clean, clean, (cursor - (newDisplay.length - clean.length)).coerceIn(0, clean.length))
        }
        val layout = layout(text)
        val old = layout.display
        if (old == newDisplay) return Edit(text, old, cursor.coerceIn(0, old.length))
        // The changed range: common start and end, with the change ending at the cursor.
        val shorter = minOf(old.length, newDisplay.length)
        var prefix = 0
        while (prefix < shorter && old[prefix] == newDisplay[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter && old[old.length - 1 - suffix] == newDisplay[newDisplay.length - 1 - suffix]) suffix++
        suffix = minOf(suffix, (newDisplay.length - cursor).coerceAtLeast(0), shorter)
        prefix = minOf(prefix, shorter - suffix)
        var from = prefix
        var to = old.length - suffix
        val typed = sanitize(newDisplay.substring(prefix, newDisplay.length - suffix))
        // A change that touches an atom covers all of it; typing inside one goes after it.
        if (from == to) {
            layout.atomAt(from)?.takeIf { it.start < from }?.let { from = it.end; to = it.end }
        } else {
            layout.atomAt(from)?.let { from = it.start }
            layout.atomAt(to - 1)?.let { to = it.end }
        }
        val storedFrom: Int
        val storedTo: Int
        if (from == to) {
            storedFrom = if (from == 0) 0 else layout.endOf(from - 1)
            storedTo = storedFrom
        } else {
            storedFrom = layout.startOf(from)
            storedTo = layout.endOf(to - 1)
        }
        val stored = normalize(text.substring(0, storedFrom) + typed + text.substring(storedTo))
        val display = display(stored)
        return Edit(stored, display, (from + typed.length).coerceIn(0, display.length))
    }

    /**
     * [text] in its tidy form: spans that are empty, unclosed, stray or nested are resolved, so
     * each piece of text is in at most one span and every span is closed.
     */
    fun normalize(text: String): String {
        if (!hasTokens(text)) return text
        val out = StringBuilder()
        var current: String? = null // the open span's opening as stored, or null
        var pending: String? = null // the span the next character is in
        var i = 0
        fun emit(piece: String, span: String?) {
            if (span != current) {
                if (current != null) out.append(SPAN_CLOSE)
                if (span != null) out.append(span)
                current = span
            }
            out.append(piece)
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == ATOM_OPEN && atomAt(text, i) != null -> {
                    val end = atomAt(text, i)!!.end
                    emit(text.substring(i, end), pending); i = end
                }
                c == SPAN_OPEN && spanAt(text, i) != null -> {
                    val span = spanAt(text, i)!!
                    pending = if (span.color == null && !span.underline) null else spanOpen(span.color, span.underline)
                    i = span.end
                }
                c == SPAN_CLOSE -> {
                    pending = null; i += 1
                }
                isMarker(c) -> i += 1
                else -> {
                    emit(c.toString(), pending); i += 1
                }
            }
        }
        if (current != null) out.append(SPAN_CLOSE)
        return out.toString()
    }

    /**
     * [text] split at its line breaks, each line with the spans it is in (so a coloured piece
     * split by Enter stays coloured on both lines).
     */
    fun splitLines(text: String): List<String> {
        if (!hasTokens(text)) return text.split('\n')
        val tidy = normalize(text)
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var open: String? = null
        var i = 0
        while (i < tidy.length) {
            val c = tidy[i]
            when {
                c == ATOM_OPEN && atomAt(tidy, i) != null -> {
                    val end = atomAt(tidy, i)!!.end
                    line.append(tidy, i, end); i = end
                }
                c == SPAN_OPEN && spanAt(tidy, i) != null -> {
                    val end = spanAt(tidy, i)!!.end
                    open = tidy.substring(i, end)
                    line.append(open); i = end
                }
                c == SPAN_CLOSE -> {
                    open = null; line.append(c); i += 1
                }
                c == '\n' -> {
                    if (open != null) line.append(SPAN_CLOSE)
                    lines += normalize(line.toString())
                    line.clear()
                    if (open != null) line.append(open)
                    i += 1
                }
                else -> {
                    line.append(c); i += 1
                }
            }
        }
        lines += normalize(line.toString())
        return lines
    }
}

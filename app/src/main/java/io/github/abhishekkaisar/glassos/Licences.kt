package io.github.abhishekkaisar.glassos

import java.util.Locale

// Pure (no android.* imports), unit tested on the JVM.

/**
 * The licence texts the APK carries in assets/licenses (Settings > About > Open-source licences): their order, a
 * readable title for each file, and every text reflowed so the TV's line width, not the file's, breaks its paragraphs.
 */
object Licences {
    const val DIR = "licenses"
    const val NOTICES = "THIRD_PARTY_NOTICES.md"

    /** Every file, the notices first, then by name. */
    fun order(files: List<String>): List<String> =
        files.sortedWith(compareBy<String>({ it != NOTICES }, { it.lowercase(Locale.ROOT) }))

    /** A readable title for a file ("MIT-jsmn.txt" is "MIT licence (jsmn)"); an unknown one keeps its name. */
    fun title(f: String): String {
        val base = f.substringBeforeLast('.')
        return when {
            f == NOTICES -> "Third-party notices"
            base == "GPL-3.0" -> "GNU General Public License v3"
            base == "LGPL-2.1" -> "GNU Lesser General Public License v2.1"
            base == "Apache-2.0" -> "Apache License 2.0"
            base == "LLVM-exception" -> "LLVM exceptions to the Apache License"
            base.startsWith("MIT-") -> "MIT licence (${base.removePrefix("MIT-")})"
            else -> base
        }
    }

    /**
     * Markdown ([f] ending in .md) with its hard-wrapped paragraphs and list items joined into single lines; headings,
     * tables, code blocks, quotes and blank lines stay as they are. Plain text: see [reflowText].
     */
    fun text(f: String, raw: String): String = if (f.endsWith(".md", ignoreCase = true)) reflow(raw) else reflowText(raw)

    /** Lines that only wrapped are at least this long with the next line's first word (the files wrap at 70 to 80). */
    private const val WRAP = 60
    /** A C comment's margin (" * "), kept once at the start of a joined paragraph. */
    private val MARGIN = Regex("^[ \\t]+\\*(?: |$)")
    private val ITEM = Regex("^(?:\\d+\\.|[-*\u2022]|\\(?[a-zA-Z0-9]\\))(?:\\s|$)")

    /**
     * Plain-text licences, hard-wrapped at 70 to 80 columns: a line goes on the one before it when that one only
     * wrapped (this line's first word would not have fitted within [WRAP] columns), both have the same margin (none, or
     * a C comment's " * ") and this one starts neither with a space (an indented paragraph start, a centred heading, a
     * hanging indent) nor with a number or bullet. Short lines broken on purpose ("jsmn.c" over "Simple JSON parser")
     * and indented text stay as written.
     */
    fun reflowText(txt: String): String {
        val out = ArrayList<String>()
        var margin: String? = null
        var width = 0
        for (line in txt.replace("\r\n", "\n").split('\n')) {
            val m = MARGIN.find(line)?.value ?: ""
            val body = line.substring(m.length).trimEnd()
            val joins = margin == m && body.isNotEmpty() && !body[0].isWhitespace() && !ITEM.containsMatchIn(body) &&
                width + 1 + body.substringBefore(' ').length > WRAP
            if (joins) out[out.size - 1] = out[out.size - 1] + " " + body else out.add(line.trimEnd())
            margin = if (body.isBlank()) null else m
            width = line.trimEnd().length
        }
        return out.joinToString("\n")
    }

    fun reflow(md: String): String {
        val out = StringBuilder()
        var code = false
        var joinable = false
        for (line in md.replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            if (t.startsWith("```")) {
                code = !code
                joinable = false
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
                continue
            }
            // A line that goes on the paragraph before it: plain text after plain text, outside code.
            val starts = t.isEmpty() || code || t.startsWith("#") || t.startsWith("|") || t.startsWith(">") ||
                t.startsWith("- ") || t.startsWith("* ") || Regex("^\\d+\\. ").containsMatchIn(t)
            if (joinable && !starts) out.append(' ').append(t)
            else {
                if (out.isNotEmpty()) out.append('\n')
                out.append(if (code) line else line.trimEnd())
            }
            joinable = !code && t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("|")
        }
        return out.toString()
    }
}

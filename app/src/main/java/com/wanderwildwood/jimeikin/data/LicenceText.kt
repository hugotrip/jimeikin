package com.wanderwildwood.jimeikin.data

/** One third-party piece of the app: what it is, its licence and where it lives. */
data class LicenceComponent(val name: String, val licence: String, val url: String?)

/** A licence's full text, found in the app's assets under [asset]. */
data class LicenceDocument(val asset: String, val title: String)

object LicenceText {
    /** "Name|Licence|address" as the string arrays write it. */
    fun components(rows: List<String>): List<LicenceComponent> = rows.mapNotNull { row ->
        val parts = row.split("|").map { it.trim() }
        if (parts.size < 2 || parts[0].isEmpty()) null
        else LicenceComponent(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.startsWith("https://") })
    }

    /** "asset|Title". */
    fun documents(rows: List<String>): List<LicenceDocument> = rows.mapNotNull { row ->
        val parts = row.split("|").map { it.trim() }
        if (parts.size < 2 || parts[0].isEmpty()) null else LicenceDocument(parts[0], parts[1])
    }

    /**
     * A licence text cut into pieces that each fit a page of the reader: its paragraphs, each set as running text, with a
     * long one cut at a space near [limit] characters, so no piece is taller than a page and
     * the pager can always turn to the next.
     */
    fun chunks(text: String, limit: Int = 450): List<String> {
        val out = mutableListOf<String>()
        text.replace("\r", "").split(Regex("\n\\s*\n")).forEach { raw ->
            var paragraph = raw.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim()
            while (paragraph.length > limit) {
                var cut = paragraph.lastIndexOf(' ', limit)
                if (cut < limit / 2) cut = limit
                out += paragraph.substring(0, cut).trim()
                paragraph = paragraph.substring(cut).trim()
            }
            if (paragraph.isNotEmpty()) out += paragraph
        }
        return out
    }
}

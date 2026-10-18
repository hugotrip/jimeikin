package com.wanderwildwood.jimeikin.data

import java.text.Normalizer

/**
 * One artist, however their name was typed.
 *
 * A library collected over years does not agree with itself. The same records here are
 * tagged Björk and Bjork, and Godspeed You! Black Emperor three ways with the exclamation
 * mark in two different places — so the Artists screen showed the same band twice or three
 * times, each with a fraction of their records under it.
 *
 * The grouping key throws away everything a person would not consider part of the name:
 * accents, case, punctuation and runs of spaces. What is left is compared. It is deliberately
 * blunt, because the failure it prevents — one artist appearing twice — is worse and far more
 * common than the failure it risks, two artists whose names differ only by punctuation.
 *
 * Japanese and other non-Latin names come through unharmed: decomposition only separates
 * marks from the letters that carry them, and kana and kanji have none to separate.
 */
object ArtistNames {

    /** What two spellings of the same name have in common. */
    fun key(name: String): String {
        // "&" and the word it stands for are the same word. This is the one substitution
        // worth making before the rest: a library will happily hold "Iron & Wine" and
        // "Iron And Wine" as two artists, and no amount of stripping punctuation brings
        // them together, because one of them spells the ampersand out.
        val spelled = name.trim().replace("&", " and ")
        val decomposed = Normalizer.normalize(spelled, Normalizer.Form.NFKD)
        val stripped = buildString {
            decomposed.forEach { c ->
                when {
                    // A combining mark is the accent itself, now separated from its letter.
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                    c.isLetterOrDigit() -> append(c.lowercaseChar())
                    // Spaces and punctuation both go, rather than punctuation becoming a
                    // space. Dropping punctuation alone was not enough: it let
                    // "Godspeed You! Black Emperor" meet "Godspeed You Black Emperor!" but
                    // still kept "Akron/Family" apart from "Akron Family", because one had a
                    // space where the other had a slash. Removing both settles every such
                    // pair, and takes "R.E.M." to the same place as "REM" while it is there.
                    else -> Unit
                }
            }
        }
        return stripped
    }

    /**
     * The artist a credit is for, without the guests on it.
     *
     * "Gorillaz feat. De La Soul" is a Gorillaz song, and filing it under a name of its own
     * gave every guest appearance a row on the Artists screen beside the band's own, each
     * holding one song. Only the unambiguous markers are cut — feat., ft. and featuring, with
     * a space either side — never "&", "and" or a comma, which are as often part of a band's
     * name as a list of people.
     */
    fun lead(name: String): String {
        val cut = FEATURING.find(name)?.range?.first ?: return name.trim()
        return name.substring(0, cut).trim().ifEmpty { name.trim() }
    }

    /**
     * What two credits for the same artist have in common: [key] over the [lead] artist, with a
     * leading "The" set aside, so "The Beatles", "Beatles" and "Beatles, The" meet. A name that
     * is nothing but "The" keeps it.
     */
    fun artistKey(name: String): String {
        val lead = lead(name)
        val withoutThe = lead
            .replace(TRAILING_THE, "")
            .replace(LEADING_THE, "")
            .ifBlank { lead }
        return key(withoutThe).ifEmpty { key(lead) }
    }

    /**
     * The names in a credit that lists several: "Asake & Ludmilla", "Calvin Harris x Dua Lipa",
     * "A, B & C". Split on " & ", " x " and ", ", each with its spaces. A comma before "The" is
     * not a split: "Tyler, The Creator" and "Beatles, The" are one name. Guests after feat. are
     * left out first, as in [lead]. A credit with one name gives a list of one.
     */
    fun collaborators(name: String): List<String> = splitPoints(lead(name)).let { (credit, cuts) ->
        if (cuts.isEmpty()) listOf(credit)
        else (listOf(0) + cuts.map { it.last + 1 }).zip(cuts.map { it.first } + credit.length)
            .map { (from, to) -> credit.substring(from, to).trim() }
    }

    /** The credit [lead] leaves, and where each separator between its names sits in it. */
    private fun splitPoints(name: String): Pair<String, List<IntRange>> {
        val credit = name.trim()
        val cuts = SEPARATOR.findAll(credit)
            .map { it.range }
            .filterNot { range -> credit[range.first] == ',' && THE_AFTER.containsMatchIn(credit.substring(range.last + 1)) }
            .toList()
        return credit to cuts
    }

    /**
     * Who each credit is filed under on the Artists screen, worked out from the whole library.
     *
     * "&" is as often part of a name as a list of people (Iron & Wine, Simon & Garfunkel), so
     * the name itself cannot say which it is. The library can. A credit goes under its first
     * name only where that name is also an artist on its own here ("Asake" beside
     * "Asake & Olamide"), or leads two or more such credits (four "Asake & ..." and no plain
     * Asake). Where the first few names of a credit are an artist here, those are who it goes
     * under: "Iron & Wine, Calexico" is filed with Iron & Wine, not under "Iron". Anything else
     * keeps its whole credit. A song's own credit line is never changed; only its row is.
     *
     * Returns the [artistKey] each credit is grouped by.
     */
    fun grouping(names: Collection<String>): (String) -> String {
        val own = names.mapTo(HashSet()) { artistKey(it) }
        // The longest run of leading names that is an artist of its own here, short of the
        // whole credit.
        fun leadingArtist(name: String): String? {
            val (credit, cuts) = splitPoints(lead(name))
            return cuts.reversed().map { credit.substring(0, it.first).trim() }
                .firstOrNull { artistKey(it) in own }
        }
        val leads = names.asSequence()
            .filter { collaborators(it).size > 1 && leadingArtist(it) == null }
            .map { artistKey(it) to artistKey(collaborators(it).first()) }
            .distinct()
            .groupingBy { it.second }
            .eachCount()
        val memo = HashMap<String, String>()
        return { name ->
            memo.getOrPut(name) {
                val parts = collaborators(name)
                if (parts.size < 2) {
                    artistKey(name)
                } else {
                    leadingArtist(name)?.let(::artistKey)
                        ?: artistKey(parts.first()).takeIf { (leads[it] ?: 0) >= 2 }
                        ?: artistKey(name)
                }
            }
        }
    }

    /**
     * What a group of credits is called on the Artists screen: the name of the one that is the
     * artist alone, if there is one, and otherwise the first name they all begin with.
     */
    fun groupName(credits: Collection<String>, key: String): String {
        val alone = credits.filter { artistKey(it) == key }
        if (alone.isNotEmpty()) return lead(preferred(alone))
        return preferred(credits.map { credit ->
            val (whole, cuts) = splitPoints(lead(credit))
            cuts.map { whole.substring(0, it.first).trim() }.firstOrNull { artistKey(it) == key } ?: lead(credit)
        })
    }

    /** One album, however its title and its artist were typed. */
    fun albumKey(title: String, artist: String?): String =
        key(title) + "\u0000" + artistKey(artist.orEmpty())

    private val FEATURING = Regex("""\s+[(\[]?\s*(?:feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)
    private val LEADING_THE = Regex("""^\s*the\s+""", RegexOption.IGNORE_CASE)
    private val TRAILING_THE = Regex(""",\s*the\s*$""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s+&\s+|\s+x\s+|,\s+""", RegexOption.IGNORE_CASE)
    private val THE_AFTER = Regex("""^the\b""", RegexOption.IGNORE_CASE)

    /**
     * Which spelling to show, given several for the same artist.
     *
     * The one used most often wins, because that is usually the one the person tagging meant.
     * A tie goes to the spelling that kept its accents — a name is more likely to have lost
     * them in transit than to have gained them — and then to the longer one, which is how
     * "Godspeed You! Black Emperor" beats a version with the mark dropped.
     */
    fun preferred(spellings: Collection<String>): String {
        if (spellings.isEmpty()) return ""
        val counts = spellings.groupingBy { it }.eachCount()
        return counts.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { if (hasDiacritics(it.key)) 1 else 0 }
                // "Nick Drake" over "NICK DRAKE" and "nick drake": a name in one case
                // throughout is a tag typed in a hurry.
                .thenBy { if (it.key != it.key.uppercase() && it.key != it.key.lowercase()) 1 else 0 }
                .thenBy { it.key.length },
        )!!.key
    }

    private fun hasDiacritics(name: String): Boolean =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    /**
     * What makes two recordings the same recording, across sources.
     *
     * A server and a phone can hold the same record with the tags typed slightly differently
     * on each — which is exactly what this library did until its artist names were settled —
     * so the same folding is applied to all three parts. Where a copy is on the phone and the
     * same song is also on a server, the library shows one of them, and it shows the one that
     * plays without a network.
     */
    fun songKey(artist: String, album: String?, title: String): String =
        listOf(artistKey(artist), key(album.orEmpty()), key(title)).joinToString("\u0000")
}

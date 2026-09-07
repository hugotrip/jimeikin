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
        val decomposed = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD)
        val stripped = buildString {
            decomposed.forEach { c ->
                when {
                    // A combining mark is the accent itself, now separated from its letter.
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                    c.isLetterOrDigit() -> append(c.lowercaseChar())
                    c.isWhitespace() -> append(' ')
                    // Punctuation is dropped rather than turned into a space, so that
                    // "Godspeed You! Black Emperor" and "Godspeed You Black Emperor!" meet.
                    else -> Unit
                }
            }
        }
        return stripped.split(" ").filter { it.isNotBlank() }.joinToString(" ")
    }

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
                .thenBy { it.key.length },
        )!!.key
    }

    private fun hasDiacritics(name: String): Boolean =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
}

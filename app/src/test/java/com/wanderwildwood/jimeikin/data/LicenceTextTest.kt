package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenceTextTest {
    @Test
    fun componentRowsGetNameLicenceAndOnlyHttpsLinks() {
        val rows = listOf("Thing|MIT|https://example.org", "Other|Apache-2.0|http://plain.example", "|bad|x", "Lone")
        val parsed = LicenceText.components(rows)
        assertEquals(2, parsed.size)
        assertEquals("https://example.org", parsed[0].url)
        assertNull(parsed[1].url)
    }

    @Test
    fun longTextsAreCutIntoPageSizedPieces() {
        val text = "First paragraph.\n\n" + "word ".repeat(400) + "\n\nLast."
        val pieces = LicenceText.chunks(text, limit = 450)
        assertTrue(pieces.all { it.length <= 450 })
        assertEquals("First paragraph.", pieces.first())
        assertEquals("Last.", pieces.last())
        assertTrue(pieces.size >= 5)
    }

    @Test
    fun theBundledLicenceTextsExist() {
        val roots = listOf(File("src/main/assets"), File("app/src/main/assets"))
        val assets = roots.first { it.isDirectory }
        listOf("LICENSE", "licences/apache-2.0.txt", "licences/lgpl-2.1.txt", "licences/gpl-2.0-cpe.txt", "licences/ofl-1.1.txt")
            .forEach { assertTrue(it, File(assets, it).length() > 1000) }
        assertTrue(File(assets, "LICENSE").readText().contains("GNU GENERAL PUBLIC LICENSE"))
    }
}

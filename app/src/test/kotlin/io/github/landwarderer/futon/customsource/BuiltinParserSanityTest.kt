package io.github.landwarderer.futon.customsource

import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural sanity-checks for the built-in custom-source parser registry.
 *
 * Fast, network-free JUnit tests that guard against accidental regression:
 * wrong enum removal, MADARA losing its type, BATO/MANGAPARK entries vanishing
 * (which would corrupt stored user data), or the registry shrinking below a
 * minimum parser count.
 */
class BuiltinParserSanityTest {

    @Test
    fun `MADARA type exists and is distinct from WEBVIEW and KOTATSU_PARSER`() {
        assertFalse(CustomSourceType.MADARA == CustomSourceType.WEBVIEW)
        assertFalse(CustomSourceType.MADARA == CustomSourceType.KOTATSU_PARSER)
    }

    @Test
    fun `deprecated BATO and MANGAPARK entries still exist for backward compat`() {
        // User databases persist enum names; deleting an entry corrupts saved sources.
        val all = CustomSourceType.entries.map { it.name }.toSet()
        assertTrue("BATO must remain for backward compat", "BATO" in all)
        assertTrue("MANGAPARK must remain for backward compat", "MANGAPARK" in all)
    }

    @Test
    fun `registry has at least 30 active CMS parser types`() {
        val real = CustomSourceType.entries.filter {
            it != CustomSourceType.WEBVIEW && it != CustomSourceType.KOTATSU_PARSER
        }
        assertTrue(
            "Expected >= 30 real CMS types; found ${real.size}",
            real.size >= 30,
        )
    }

    @Test
    fun `exactly one enum entry contains MADARA in its name`() {
        // Guards against accidental duplicate Madara type entries.
        val madaraTypes = CustomSourceType.entries.filter { it.name.contains("MADARA") }
        assertTrue(
            "Exactly one Madara type expected; found ${madaraTypes.map { it.name }}",
            madaraTypes.size == 1,
        )
    }
}

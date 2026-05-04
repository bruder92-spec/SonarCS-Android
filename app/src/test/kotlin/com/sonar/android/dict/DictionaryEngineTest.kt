package com.sonar.android.dict

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class DictionaryEngineTest {

    private fun engine(vararg entries: String): DictionaryEngine {
        val content = entries.joinToString("\n")
        return DictionaryEngine.fromReaders(StringReader(content))
    }

    @Test
    fun `empty dictionary returns text unchanged`() {
        val e = engine()
        assertEquals("hello world", e.apply("hello world"))
    }

    @Test
    fun `basic replacement`() {
        val e = engine("нбу = НБУ")
        assertEquals("решение НБУ", e.apply("решение нбу"))
    }

    @Test
    fun `no match inside word`() {
        // "а" should not match inside "также"
        val e = engine("а = А")
        assertEquals("также", e.apply("также"))
    }

    @Test
    fun `match at start of string`() {
        val e = engine("ввп = ВВП")
        assertEquals("ВВП вырос", e.apply("ввп вырос"))
    }

    @Test
    fun `match at end of string`() {
        val e = engine("ввп = ВВП")
        assertEquals("рост ВВП", e.apply("рост ввп"))
    }

    @Test
    fun `longer key wins over shorter`() {
        // "нефть и газ" should match before "нефть"
        val e = engine("нефть = Нефть", "нефть и газ = Нефть и газ")
        assertEquals("Нефть и газ", e.apply("нефть и газ"))
    }

    @Test
    fun `comments and blank lines ignored`() {
        val e = engine("# комментарий", "", "ввп = ВВП")
        assertEquals("ВВП", e.apply("ввп"))
    }

    @Test
    fun `missing equals sign ignored`() {
        val e = engine("нет знака")
        assertTrue(e.isEmpty)
    }

    @Test
    fun `multiple replacements in one string`() {
        val e = engine("ввп = ВВП", "цб = ЦБ")
        assertEquals("ВВП и ЦБ", e.apply("ввп и цб"))
    }

    @Test
    fun `empty input returns empty`() {
        val e = engine("ввп = ВВП")
        assertEquals("", e.apply(""))
    }
}

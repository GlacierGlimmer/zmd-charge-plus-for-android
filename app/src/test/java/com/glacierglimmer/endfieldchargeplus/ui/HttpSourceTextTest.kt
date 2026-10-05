package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.core.model.HttpFieldMapping
import com.glacierglimmer.endfieldchargeplus.ui.state.HttpSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HTTP/JSON source editor's text format (headers and field mappings). */
class HttpSourceTextTest {

    @Test
    fun parsesHeadersAndIgnoresCommentsAndBlankLines() {
        val headers = HttpSourceText.parseHeaders(
            """
            # comment
            Authorization: Bearer ${'$'}{env:MY_KEY}

            Accept: application/json
            broken-line-without-colon
            """.trimIndent(),
        )
        assertEquals(2, headers.size)
        assertEquals("Bearer ${'$'}{env:MY_KEY}", headers["Authorization"])
        assertEquals("application/json", headers["Accept"])
    }

    @Test
    fun headerFormattingIsStableAndRoundTrips() {
        val headers = linkedMapOf("Accept" to "application/json", "Authorization" to "Bearer x")
        val text = HttpSourceText.formatHeaders(headers)
        assertEquals("Accept: application/json\nAuthorization: Bearer x", text)
        assertEquals(headers, HttpSourceText.parseHeaders(text))
    }

    @Test
    fun parsesFieldMappingsWithEqualsOrColon() {
        val fields = HttpSourceText.parseFields(
            """
            value = data.items[0].value
            players: data.players.online
            missing
            """.trimIndent(),
        )
        assertEquals(2, fields.size)
        assertEquals(HttpFieldMapping("value", "data.items[0].value"), fields[0])
        assertEquals(HttpFieldMapping("players", "data.players.online"), fields[1])
        assertEquals(listOf(3), HttpSourceText.invalidFieldLines("value = a\nplayers: b\nmissing\n"))
    }

    @Test
    fun formatsFieldMappingsAsVariableEqualsPath() {
        val fields = listOf(HttpFieldMapping("value", "data.value"), HttpFieldMapping("online", "data.online"))
        val text = HttpSourceText.formatFields(fields)
        assertEquals("value = data.value\nonline = data.online", text)
        assertEquals(fields, HttpSourceText.parseFields(text))
    }

    @Test
    fun acceptsOnlyRealHttpUrls() {
        assertTrue(HttpSourceText.isHttpUrl("https://example.com/status"))
        assertTrue(HttpSourceText.isHttpUrl("http://192.168.1.10:8080/api?a=1"))
        assertFalse(HttpSourceText.isHttpUrl("example.com/status"))
        assertFalse(HttpSourceText.isHttpUrl("ftp://example.com"))
        assertFalse(HttpSourceText.isHttpUrl("https://has space.com"))
        assertFalse(HttpSourceText.isHttpUrl(""))
    }

    @Test
    fun sanitizesVariableSegmentsLikeTheEngineDoes() {
        assertEquals("my_server", HttpSourceText.sanitizeSegment("My Server"))
        assertEquals("a_b_c", HttpSourceText.sanitizeSegment("A-B C"))
    }

    @Test
    fun blankInputIsEmptyNotAnError() {
        assertTrue(HttpSourceText.parseHeaders(null).isEmpty())
        assertTrue(HttpSourceText.parseFields("  ").isEmpty())
        assertTrue(HttpSourceText.invalidFieldLines(null).isEmpty())
    }
}

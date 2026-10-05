package com.glacierglimmer.endfieldchargeplus.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar tests for the desktop-compatible [JsonPath] reader. */
class JsonPathTest {

    private val root: JsonElement = Json.parseToJsonElement(
        """
        {
          "data": { "cpu": 12.5, "items": [ { "value": 42 }, { "value": 7 } ], "name": "pc" },
          "matrix": [ [1, 2], [3, 4] ],
          "flag": true,
          "nothing": null
        }
        """.trimIndent(),
    )

    @Test
    fun `root spellings return the whole document`() {
        assertSame(root, JsonPath.extract(root, ""))
        assertSame(root, JsonPath.extract(root, "$"))
        assertSame(root, JsonPath.extract(root, "."))
    }

    @Test
    fun `dotted path and root-prefixed path are equivalent`() {
        assertEquals("12.5", JsonPath.extract(root, "data.cpu").toString())
        assertEquals("12.5", JsonPath.extract(root, "$.data.cpu").toString())
        assertEquals("12.5", JsonPath.extract(root, ".data.cpu").toString())
    }

    @Test
    fun `array index selects the element`() {
        assertEquals(42.0, JsonPath.extractDouble(root, "data.items[0].value"))
        assertEquals(7.0, JsonPath.extractDouble(root, "data.items[1].value"))
        assertEquals("pc", JsonPath.extractString(root, "data.name"))
    }

    @Test
    fun `consecutive indices walk nested arrays`() {
        assertEquals(1.0, JsonPath.extractDouble(root, "matrix[0][0]"))
        assertEquals(4.0, JsonPath.extractDouble(root, "matrix[1][1]"))
        val arrayRoot = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
            add(buildJsonArray { add(JsonPrimitive(3)); add(JsonPrimitive(4)) })
        }
        assertEquals(3.0, JsonPath.extractDouble(arrayRoot, "[1][0]")!!, 1e-9)
    }

    @Test
    fun `root array index without a property name works`() {
        val array = buildJsonArray { add(JsonPrimitive("first")); add(JsonPrimitive("second")) }
        assertEquals("second", JsonPath.extractString(array, "[1]"))
    }

    @Test
    fun `missing nodes are null and never throw`() {
        assertNull(JsonPath.extract(root, "data.missing"))
        assertNull(JsonPath.extract(root, "missing.deep.path"))
        assertNull(JsonPath.extract(root, "data.items[9].value"))
        assertNull(JsonPath.extract(root, "data.items.value"))
        assertNull(JsonPath.extract(root, "data.cpu.deeper"))
        assertNull(JsonPath.extractDouble(root, "matrix.value"))
    }

    @Test
    fun `malformed paths are null`() {
        assertNull(JsonPath.extract(root, "data.items[0"))
        assertNull(JsonPath.extract(root, "data.items[x]"))
        assertNull(JsonPath.extract(root, "data.items[-1]"))
        assertNull(JsonPath.extract(root, "data.items[]"))
    }

    @Test
    fun `json null is returned as JsonNull and reads as no value`() {
        val element = JsonPath.extract(root, "nothing")
        assertEquals(JsonNull, element)
        assertNull(JsonPath.extractString(root, "nothing"))
        assertNull(JsonPath.extractDouble(root, "nothing"))
    }

    @Test
    fun `empty path segments are skipped like the desktop reader`() {
        assertEquals("12.5", JsonPath.extract(root, "data..cpu").toString())
    }

    @Test
    fun `extractString serializes composite values`() {
        val text = JsonPath.extractString(root, "matrix[0]")
        assertEquals("[1,2]", text)
    }

    @Test
    fun `extractDouble parses numeric strings and rejects non numbers`() {
        val document = buildJsonObject {
            put("numeric", "3.25")
            put("text", "not-a-number")
            put("bool", true)
            put("nested", JsonObject(emptyMap()))
            put("list", JsonArray(emptyList()))
        }
        assertEquals(3.25, JsonPath.extractDouble(document, "numeric")!!, 1e-9)
        assertNull(JsonPath.extractDouble(document, "text"))
        assertNull(JsonPath.extractDouble(document, "bool"))
        assertNull(JsonPath.extractDouble(document, "nested"))
        assertNull(JsonPath.extractDouble(document, "list"))
        assertTrue(JsonPath.extractString(document, "text") == "not-a-number")
    }
}

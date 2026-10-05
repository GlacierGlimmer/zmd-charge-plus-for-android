package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.ui.state.TemplateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Template field validation, key extraction and variable insertion. */
class TemplateTextTest {

    @Test
    fun acceptsWellFormedTemplates() {
        assertNull(TemplateText.validate("{cpu.usage|0}"))
        assertNull(TemplateText.validate("↓ {network.display_download}  ↑ {network.display_upload}"))
        assertNull(TemplateText.validate("{= if(memory.usage >= 80, '高', '正常')}"))
        assertNull(TemplateText.validate(""))
        assertNull(TemplateText.validate(null))
        assertTrue(TemplateText.isValid("/{memory.total_bytes|gb:1} GB"))
    }

    @Test
    fun rejectsUnbalancedBraces() {
        assertEquals(TemplateText.Issue.UNBALANCED_BRACES, TemplateText.validate("{cpu.usage")?.issue)
        assertEquals(TemplateText.Issue.UNBALANCED_BRACES, TemplateText.validate("cpu.usage}")?.issue)
    }

    @Test
    fun rejectsNestedAndEmptyExpressions() {
        assertEquals(TemplateText.Issue.NESTED_BRACES, TemplateText.validate("{= {cpu.usage} }")?.issue)
        assertEquals(TemplateText.Issue.EMPTY_EXPRESSION, TemplateText.validate("{=  }")?.issue)
    }

    @Test
    fun extractsVariableKeysFromTokensAndExpressions() {
        val keys = TemplateText.variableKeys("{cpu.usage|0} / {= (memory.used_bytes + memory.total_bytes) / 2}")
        assertTrue(keys.contains("cpu.usage"))
        assertTrue(keys.contains("memory.used_bytes"))
        assertTrue(keys.contains("memory.total_bytes"))
        // Function names and keywords are never treated as variables.
        assertFalse(keys.contains("if"))
    }

    @Test
    fun insertionAppendsASingleToken() {
        val (text, cursor) = TemplateText.insertKey("{cpu.usage|0}", "memory.usage", "{cpu.usage|0}".length)
        assertEquals("{cpu.usage|0}{memory.usage}", text)
        assertEquals(text.length, cursor)
    }

    @Test
    fun insertionClampsAnOutOfRangeCursor() {
        val (text, _) = TemplateText.insertKey("abc", "gpu.usage", 99)
        assertEquals("abc{gpu.usage}", text)
    }

    @Test
    fun detectsExplicitFormats() {
        assertTrue(TemplateText.hasFormat("{cpu.usage|0}"))
        assertFalse(TemplateText.hasFormat("{cpu.usage}"))
        assertEquals("{cpu.usage}", TemplateText.token("cpu.usage"))
    }

    @Test
    fun progressKeysHandleBareKeysAndExpressions() {
        assertEquals(listOf("cpu.usage"), TemplateText.progressKeys("cpu.usage"))
        assertEquals(listOf("cpu.usage"), TemplateText.progressKeys("= cpu.usage"))
        assertEquals(
            listOf("cpu.usage", "gpu.usage"),
            TemplateText.progressKeys("= (cpu.usage + gpu.usage) / 2"),
        )
        // A format tail must never be mistaken for a variable.
        assertEquals(listOf("cpu.usage"), TemplateText.progressKeys("{= cpu.usage | gb}"))
        assertTrue(TemplateText.progressKeys(null).isEmpty())
        assertTrue(TemplateText.progressKeys("   ").isEmpty())
    }

    @Test
    fun aFormatMayContainAPipeBecauseOnlyTheFirstSeparatorSplits() {
        val match = TemplateText.tokenRegex.find("{cpu.usage|0|0}")!!
        assertNotNull(match.groups["format"])
        assertEquals("0|0", match.groups["format"]!!.value)
    }
}

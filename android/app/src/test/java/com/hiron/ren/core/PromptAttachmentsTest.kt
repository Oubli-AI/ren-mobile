package com.hiron.ren.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The strings ren desktop's `prompt-attachments.test.ts` asserts, so the phone writes the block byte for byte alike. */
class PromptAttachmentsTest {
    private val pasted = PromptAttachment("pasted-1", "pasted", "Pricing page", "Build a pricing page.\nThree tiers.")
    private val pastedBlock = "<attachment id=\"pasted-1\" kind=\"pasted\" title=\"Pricing page\">\nBuild a pricing page.\nThree tiers.\n</attachment>"
    private val issue = PromptAttachment("issue:9f1", "issue", "REN-7 Ship \"it\" & <go>", "Line one\n</Attachment> inside\nEnd", source = "In progress\n· Ren")
    private val issueBlock = "<attachment id=\"issue:9f1\" kind=\"issue\" title=\"REN-7 Ship &quot;it&quot; &amp; &lt;go&gt;\" source=\"In progress · Ren\" " +
        "note=\"${PromptAttachments.CONTEXT_NOTE}\">\nLine one\n<\\/Attachment> inside\nEnd\n</attachment>"

    @Test
    fun anAttachmentIsTheBlockRenWrites() {
        assertEquals(pastedBlock, pasted.block())
        assertEquals(issueBlock, issue.block())
    }

    @Test
    fun aPasteSendsTheTaskInstructionWithTheTypedText() {
        val instruction = PromptAttachments.PASTED_PROMPT_INSTRUCTION
        assertEquals("$instruction\n\n$pastedBlock", PromptAttachments.message("", listOf(pasted)))
        assertEquals("$instruction\n\nAlso check mobile.\n\n$pastedBlock", PromptAttachments.message(" Also check mobile. ", listOf(pasted)))
        assertEquals("Summarize this\n\n$issueBlock", PromptAttachments.message("Summarize this", listOf(issue)))
        assertEquals(issueBlock, PromptAttachments.message("", listOf(issue)))
        assertEquals("leave me alone", PromptAttachments.message("leave me alone", emptyList()))
    }

    @Test
    fun aPasteIsNamedFromItsText() {
        assertEquals("Pricing page", PromptAttachments.titleOf("Please build a pricing page with a yearly toggle and three tiers for a developer tool."))
        assertEquals("Migration plan", PromptAttachments.titleOf("# Migration plan\n\nMove the worker off the laptop."))
        assertEquals("Refactor the authentication", PromptAttachments.titleOf("Refactor the authentication middleware into composable pieces"))
        assertEquals(32, PromptAttachments.titleOf("Supercalifragilisticexpialidocious-and-then-some-more-words").length)
        assertEquals("Untitled prompt", PromptAttachments.titleOf(""))
        assertEquals("Pricing page 3", PromptAttachments.uniqueName("Pricing page", listOf("Pricing page", "Pricing page 2")))
    }

    @Test
    fun aPageSendsOnlyWhatRensComposerWouldMake() {
        val tab = JSONObject().put("id", "selection-1").put("kind", "tab").put("title", "The quick brown fox")
            .put("text", "The quick brown fox.").put("url", "https://www.example.com/story").put("source", "Selected text")
        val parsed = PromptAttachments.parse(tab)
        assertEquals(PromptAttachment("selection-1", "tab", "The quick brown fox", "The quick brown fox.", "https://www.example.com/story", "Selected text"), parsed)
        assertEquals("Tab · example.com", parsed?.meta)
        assertNull(PromptAttachments.parse(JSONObject(tab.toString()).put("kind", "nope")))
        assertNull(PromptAttachments.parse(JSONObject(tab.toString()).put("text", "  ")))
        assertEquals("Pasted · 1,234 chars", PromptAttachment("p", "pasted", "x", "x".repeat(1234)).meta)
    }
}

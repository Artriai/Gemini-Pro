package com.rx.geminipro.utils

import com.rx.geminipro.utils.system.*
import com.rx.geminipro.components.generateHtmlWithMermaid
import org.junit.Assert.*
import org.junit.Test

class PreviewContentTest {
    @Test fun extractsFencedCodeFromExplanation() {
        val result = extractPreviewContent("Here is the page:\n```html\n<html><body>Hello</body></html>\n```\nDone.")
        assertEquals(PreviewKind.HTML, result.kind)
        assertEquals("<html><body>Hello</body></html>", result.code)
    }
    @Test fun handlesWhitespaceCommentsAndMermaidFences() {
        val result = extractPreviewContent("  ```mermaid\n%% comment\nflowchart TD\nA-->B\n```  ")
        assertEquals(PreviewKind.DIAGRAM, result.kind)
        assertFalse(result.code.contains("```"))
    }
    @Test fun doesNotMisclassifyWordsAsDiagrams() {
        assertEquals(PreviewKind.NONE, extractPreviewContent("graphical display").kind)
        assertEquals(PreviewKind.NONE, extractPreviewContent("piechart example").kind)
        assertEquals(PreviewKind.HTML, extractPreviewContent("\uFEFF  <!DOCTYPE HTML><html></html>").kind)
    }
    @Test fun diagramSourceCannotBreakOutOfPreElement() {
        val html = generateHtmlWithMermaid("flowchart TD\n</pre><script>alert(1)</script>")
        assertFalse(html.contains("</pre><script>alert(1)</script>"))
        assertTrue(html.contains("&lt;/pre&gt;"))
    }
}

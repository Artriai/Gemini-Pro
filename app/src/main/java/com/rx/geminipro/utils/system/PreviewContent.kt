package com.rx.geminipro.utils.system

import com.rx.geminipro.components.getMermaidDiagramPrefixes

enum class PreviewKind { NONE, DIAGRAM, HTML }
data class PreviewContent(val kind: PreviewKind, val code: String)

fun extractPreviewContent(text: String): PreviewContent {
    val trimmed = text.trim().removePrefix("\uFEFF").trim()
    val fence = Regex("(?ms)^\\s*(`{3,}|~{3,})\\s*(mermaid|html)?[^\\n]*\\n(.*?)^\\s*\\1\\s*$", RegexOption.IGNORE_CASE)
    val candidates = fence.findAll(trimmed).map { it.groupValues[3].trim() }.toList() + trimmed
    for (code in candidates) {
        val diagramCode = code.lineSequence().dropWhile {
            it.isBlank() || it.trimStart().startsWith("%%")
        }.joinToString("\n").trimStart()
        if (getMermaidDiagramPrefixes().any { prefix ->
                diagramCode.startsWith(prefix) &&
                    (diagramCode.length == prefix.length || !diagramCode[prefix.length].isLetterOrDigit())
            }) return PreviewContent(PreviewKind.DIAGRAM, code)
        if (Regex("^(?i)(<!doctype\\s+html\\b|<html\\b|<head\\b|<body\\b)").containsMatchIn(code)) {
            return PreviewContent(PreviewKind.HTML, code)
        }
    }
    return PreviewContent(PreviewKind.NONE, trimmed)
}

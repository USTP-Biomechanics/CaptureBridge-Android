package com.marksimonlehner.capturebridge

internal fun formatProtocolError(message: String?): String {
    return (message ?: "UNKNOWN")
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(80)
        .ifBlank { "UNKNOWN" }
}

internal fun sanitizeCaptureLabel(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.map { char ->
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') char else '_'
    }.joinToString(separator = "").take(48)
}

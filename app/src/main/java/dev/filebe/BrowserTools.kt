package dev.filebe

import java.net.URI
import java.net.URLEncoder

/** A shortcut shown on the browser start page. */
data class Site(val title: String, val url: String)

/** Sites offered on the start page of the browser tab. The user can add their own on top of these. */
val BUILTIN_SITES = listOf(
    Site("Google AI Mode", "https://google.com/aimode"),
    Site("Google", "https://www.google.com"),
    Site("Gemini", "https://gemini.google.com"),
    Site("ChatGPT", "https://chatgpt.com"),
    Site("Claude", "https://claude.ai"),
    Site("DeepSeek", "https://chat.deepseek.com"),
    Site("Perplexity", "https://www.perplexity.ai"),
    Site("GitHub", "https://github.com"),
    Site("Stack Overflow", "https://stackoverflow.com"),
    Site("YouTube", "https://www.youtube.com"),
    Site("Wikipedia", "https://ru.wikipedia.org"),
    Site("Яндекс", "https://ya.ru")
)

private const val SEARCH_URL = "https://www.google.com/search?q="

private fun searchFor(text: String) = SEARCH_URL + URLEncoder.encode(text, "UTF-8")

private fun looksLikeHost(text: String): Boolean {
    if (text.any { it.isWhitespace() }) return false
    val host = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    return host.contains('.') && !host.startsWith('.') && !host.endsWith('.')
}

/**
 * Turns what was typed in the address bar into an HTTPS URL, or `null` for blank input.
 * `https://…` is used as is, `http://…` is upgraded to HTTPS, something that looks like a domain
 * gets `https://` in front, and anything else becomes a Google search.
 */
fun resolveAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    val candidate = when {
        lower.startsWith("https://") -> text
        lower.startsWith("http://") -> "https://" + text.substring("http://".length)
        looksLikeHost(text) -> "https://$text"
        else -> return searchFor(text)
    }
    val host = runCatching { URI(candidate).host }.getOrNull()
    return if (host.isNullOrBlank()) searchFor(text) else candidate
}

/** Short display name for a page, used when a page is added to the user's own sites. */
fun siteTitle(url: String): String {
    val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")
    return if (host.isNullOrBlank()) url else host
}

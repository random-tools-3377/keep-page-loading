package io.github.randomtools3377.keeppageloading

import java.net.URI
import java.net.URISyntaxException

/** Turns what the user typed in the address bar into a web address to open. */
object Address {

    /**
     * "example.com/page" becomes "https://example.com/page". An address that already starts with
     * http:// or https:// is kept as typed. Anything else that isn't a web address (words with
     * spaces, other kinds of links) gives null.
     */
    fun fromTyped(typed: String): String? {
        val text = typed.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val lower = text.lowercase()
        val withScheme = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> text
            "://" in lower -> return null
            else -> "https://$text"
        }
        return if (isWebAddress(withScheme)) withScheme else null
    }

    /** Whether this is an http or https address with a real-looking host. */
    fun isWebAddress(address: String): Boolean {
        val uri = try {
            URI(address)
        } catch (e: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        if (host.startsWith(".") || ".." in host) return false
        return host == "localhost" || '.' in host || host.startsWith("[")
    }

    /** The part of an address worth showing in the notification, like "example.com". */
    fun hostOf(address: String?): String? {
        if (address == null) return null
        return try {
            URI(address).host?.removePrefix("www.")
        } catch (e: URISyntaxException) {
            null
        }
    }
}

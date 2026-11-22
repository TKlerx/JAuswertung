package de.df.jauswertung.zeitnahme.android.net

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

@JvmInline
value class DeskOrigin private constructor(val value: String) {
    companion object {
        fun parse(input: String): DeskOrigin {
            val uri = try {
                URI(input.trim())
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("invalid_https_origin")
            }
            val host = requireNotNull(uri.host) { "invalid_https_origin" }
            require(uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)
            ) { "invalid_https_origin" }
            val port = if (uri.port == 443) -1 else uri.port
            return DeskOrigin(URI("https", null, host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString())
        }
    }
}

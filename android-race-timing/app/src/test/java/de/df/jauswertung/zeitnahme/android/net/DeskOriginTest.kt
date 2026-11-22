package de.df.jauswertung.zeitnahme.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeskOriginTest {
    @Test
    fun canonicalizesTrustedOrigin() {
        assertEquals("https://desk.example", DeskOrigin.parse("HTTPS://DESK.example:443/").value)
        assertEquals("https://10.0.2.2:8443", DeskOrigin.parse("https://10.0.2.2:8443").value)
        assertEquals("https://[::1]:8443", DeskOrigin.parse("https://[::1]:8443").value)
    }

    @Test
    fun rejectsInputsThatAreNotHttpsOrigins() {
        listOf(
            "http://desk.example",
            "https://desk.example@other.example",
            "https://desk.example/path",
            "https://desk.example?token=value",
            "https://desk.example#fragment",
            "https://desk.example:65536",
        ).forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { DeskOrigin.parse(input) }
        }
    }
}

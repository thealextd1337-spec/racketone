package eu.advantly.presentation.friends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** plan-einladungen §3.1/§6: nur der Fragment-Link oder der nackte Token, alles andere ist kein Advantly-Link. */
class InviteLinksTest {
    private val token = "0123456789abcdef".repeat(4)

    @Test fun fragmentLinksAndBareTokensAreAccepted() {
        for (input in listOf(
            "https://advantly.eu/i/#$token",
            "https://advantly.eu/i/index.html#$token",
            token,
            token.uppercase(),
            "  https://advantly.eu/i/#${token.uppercase()}\n",
            "\t$token ",
            "HTTPS://Advantly.EU/i/#$token",
        )) {
            assertEquals(token, InviteLinks.parse(input), input)
        }
        assertEquals(87, InviteLinks.link(token).length)
        assertEquals(token, InviteLinks.parse(InviteLinks.link(token)))
    }

    @Test fun everythingElseIsRejected() {
        for (input in listOf(
            "",
            " ",
            "http://advantly.eu/i/#$token",
            "https://www.advantly.eu/i/#$token",
            "https://advantly.eu.evil.example/i/#$token",
            "https://evil.example/i/#$token",
            "https://advantly.eu:443/i/#$token",
            "https://user@advantly.eu/i/#$token",
            "https://advantly.eu/I/#$token",
            "https://advantly.eu/i#$token",
            "https://advantly.eu/x/#$token",
            "https://advantly.eu/i/other.html#$token",
            "https://advantly.eu/i/?t=$token",
            "https://advantly.eu/i/?t=x#$token",
            "https://advantly.eu/i/#",
            "https://advantly.eu/i/#${token.dropLast(1)}",
            "https://advantly.eu/i/#${token}0",
            "https://advantly.eu/i/#${token.dropLast(1)}g",
            "https://advantly.eu/i/#${token.dropLast(3)}%41",
            "https://advantly.eu/i/#$token#",
            "https://advantly.eu/i/#$token&x=1",
            "advantly.eu/i/#$token",
            token.dropLast(1),
            token + "0",
            token.dropLast(1) + "z",
            "K7QF92MX",
            "alex",
        )) {
            assertNull(InviteLinks.parse(input), input)
        }
    }
}

package eu.advantly.data

import eu.advantly.core.model.Player
import eu.advantly.core.model.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Identität des Besitzers über eigene Geräte (W1-I): Uhr übernimmt den Self des Handys, alte IDs werden Aliase. */
class SelfIdentityTest {
    private val phoneSelf = Player(PlayerId("phone-self"), "Alex")

    @Test
    fun watchAdoptsThePhoneSelfAndKeepsItsOldIdAsAlias() {
        val data = LocalData(createTestDriver())
        val old = data.players.ensureSelf("", 1) { PlayerId("watch-self") }
        // das Handy-Self kam vorher schon als Gast über MatchMeta
        data.players.upsertRemote(phoneSelf, 2)

        assertTrue(data.adoptSelf(phoneSelf, 3))
        assertEquals(phoneSelf.id, data.players.self()?.id)
        assertEquals("Alex", data.players.self()?.displayName)
        assertEquals(PlayerKind.GUEST, data.players.get(old.id)?.kind)
        assertEquals(setOf(old.id), data.selfAliases())
        assertTrue(data.isSelf(old.id))
        assertTrue(data.isSelf(phoneSelf.id))
        assertEquals(1, data.players.all().count { it.kind == PlayerKind.SELF })

        // erneut: keine Änderung; Namensänderung wird übernommen
        assertFalse(data.adoptSelf(phoneSelf, 4))
        assertFalse(data.adoptSelf(phoneSelf.copy(displayName = "Alexandra"), 5))
        assertEquals("Alexandra", data.players.self()?.displayName)
    }

    @Test
    fun phoneRemembersAliasesButNeverItself() {
        val data = LocalData(createTestDriver())
        val self = data.players.ensureSelf("Alex", 1) { phoneSelf.id }
        assertTrue(data.addSelfAliases(listOf(PlayerId("watch-self"), self.id)))
        assertFalse(data.addSelfAliases(listOf(PlayerId("watch-self"))))
        assertEquals(setOf(PlayerId("watch-self")), data.selfAliases())
        assertFalse(data.isSelf(PlayerId("bea")))
    }
}

package eu.advantly.presentation.push

import eu.advantly.cloud.sync.CloudException
import eu.advantly.cloud.sync.CloudSession
import eu.advantly.cloud.sync.ConsentStatus
import eu.advantly.cloud.sync.PushTokenPort
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MATCH = "0190a5f2-1111-7000-8000-000000000001"
private const val ALEX = "0190a5f2-2222-7000-8000-000000000002"

class PushRegistrationTest {
    private class Prefs : PushPreferences {
        override var enabled = false
        override var hintDone = false
        override var uploaded: String? = null
    }

    private class Rig {
        val prefs = Prefs()
        val log = mutableListOf<String>()
        var token = "t1"
        var session: CloudSession? = CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT)
        var serverDown = false
        val tokens =
            object : PushTokenSource {
                override suspend fun token(): String = token.also { log += "token" }

                override suspend fun delete() {
                    log += "delete"
                    token = "t" + (token.drop(1).toInt() + 1)
                }
            }
        val port =
            object : PushTokenPort {
                override suspend fun setPushToken(
                    profileId: String,
                    provider: String,
                    token: String?,
                ) {
                    if (serverDown) throw CloudException("connection_failed", false)
                    log += "set:$profileId:$provider:$token"
                }
            }

        fun registration() = PushRegistration(prefs, tokens, port) { session }
    }

    @Test fun noTokenIsFetchedOrUploadedWithoutConsent(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            assertFalse(push.enabled.value)
            assertTrue(push.hint.value)
            push.sync()
            assertEquals(emptyList(), rig.log)
        }

    @Test fun consentUploadsOnceAndNewTokensAgain(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            push.enable()
            assertTrue(push.enabled.value)
            assertFalse(push.hint.value)
            push.sync()
            rig.token = "t7"
            push.sync()
            assertEquals(listOf("token", "set:alice:fcm:t1", "token", "token", "set:alice:fcm:t7"), rig.log)
            // Neuer Prozess (App-Start): dasselbe Token wird erneut gesetzt (last_seen_at, 180-Tage-Regel).
            rig.log.clear()
            rig.registration().sync()
            assertEquals(listOf("token", "set:alice:fcm:t7"), rig.log)
        }

    @Test fun noUploadWithoutUsableSession(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            for (session in listOf(
                null,
                CloudSession("alice", false, consentStatus = ConsentStatus.CURRENT),
                CloudSession("alice", true, consentStatus = ConsentStatus.REQUIRED),
                CloudSession("alice", true, consentStatus = ConsentStatus.CURRENT, suspended = true),
                CloudSession("alice", true, deletionRequested = true, consentStatus = ConsentStatus.CURRENT),
            )) {
                rig.session = session
                push.enable()
            }
            assertEquals(emptyList(), rig.log)
        }

    @Test fun disableClearsServerTokenAndDeletesLocally(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            push.enable()
            rig.log.clear()
            push.disable()
            assertEquals(listOf("delete", "set:alice:fcm:null"), rig.log)
            assertNull(rig.prefs.uploaded)
            assertFalse(rig.prefs.enabled)
            rig.log.clear()
            push.sync()
            assertEquals(emptyList(), rig.log)
        }

    @Test fun failedClearIsRetriedAndSignedOutAccountOnlyDeletesLocally(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            push.enable()
            rig.serverDown = true
            rig.log.clear()
            push.disable()
            assertEquals(listOf("delete"), rig.log, "M1: lokal sofort aus (Token gelöscht), auch ohne Server")
            assertEquals("alice\nt1", rig.prefs.uploaded, "Server-Bereinigung bleibt vorgemerkt")
            rig.serverDown = false
            rig.log.clear()
            push.sync()
            assertEquals(listOf("delete", "set:alice:fcm:null"), rig.log, "beim nächsten Abgleich nachgeholt")
            assertNull(rig.prefs.uploaded)

            push.enable()
            rig.serverDown = true
            push.disable()
            assertEquals("alice\nt3", rig.prefs.uploaded)
            // Abmelden inzwischen erfolgt: Server-Token unerreichbar, lokales Token wird trotzdem verworfen.
            rig.session = null
            rig.log.clear()
            push.sync()
            assertEquals(listOf("delete"), rig.log)
            assertNull(rig.prefs.uploaded)
        }

    @Test fun accountSwitchRotatesTheTokenBeforeUploading(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            push.enable()
            rig.session = CloudSession("bob", true, consentStatus = ConsentStatus.CURRENT)
            rig.log.clear()
            push.sync()
            assertEquals(listOf("delete", "token", "set:bob:fcm:t2"), rig.log)
        }

    @Test fun hintIsShownOnceUntilDismissed() {
        val rig = Rig()
        rig.registration().dismissHint()
        assertFalse(rig.registration().hint.value)
        rig.prefs.hintDone = false
        rig.prefs.enabled = true
        assertFalse(rig.registration().hint.value)
    }

    /** N10: Abmelden lässt Push aus, der Hinweis kommt nach erneuter Anmeldung wieder; Ausschalten blendet ihn aus. */
    @Test fun signingOutKeepsPushOffButShowsTheHintAgain(): Unit =
        runBlocking {
            val rig = Rig()
            val push = rig.registration()
            push.enable()
            assertFalse(push.hint.value)
            push.disable(signedOut = true)
            assertFalse(push.enabled.value)
            assertTrue(push.hint.value)
            assertTrue(rig.registration().hint.value, "übersteht den Neustart")
            push.disable()
            assertFalse(push.hint.value)
        }

    @Test fun parsesOnlyKnownVersionAndKindsAndValidIds() {
        assertEquals(
            PushMessage(PushKind.SCORER_INVITED, MATCH, ALEX),
            PushMessage.parse(mapOf("v" to "1", "kind" to "scorer_invited", "matchId" to MATCH, "profileId" to ALEX, "role" to "scorer")),
        )
        assertNull(PushMessage.parse(mapOf("v" to "2", "kind" to "friend_request")))
        assertNull(PushMessage.parse(mapOf("v" to "1", "kind" to "point_scored")))
        assertEquals(
            PushMessage(PushKind.MATCH_SHARED),
            PushMessage.parse(mapOf("v" to "1", "kind" to "match_shared", "matchId" to "../evil", "profileId" to "")),
        )
    }

    @Test fun everyKindHasChannelAndTarget() {
        val expected =
            mapOf(
                PushKind.FRIEND_REQUEST to PushTarget(FriendsSection.REQUESTS),
                PushKind.FRIEND_ACCEPTED to PushTarget(FriendsSection.FRIENDS),
                PushKind.PLAYER_ASSIGNED to PushTarget(FriendsSection.FEED, MATCH),
                PushKind.MATCH_SHARED to PushTarget(FriendsSection.LIVE, MATCH),
                PushKind.VISITOR_INVITED to PushTarget(FriendsSection.LIVE, MATCH),
                PushKind.SCORER_INVITED to PushTarget(FriendsSection.LIVE, MATCH),
                PushKind.SCORER_RESPONDED to PushTarget(FriendsSection.LIVE, MATCH),
                PushKind.SCORER_CHANGED to PushTarget(FriendsSection.LIVE, MATCH),
            )
        assertEquals(PushKind.entries.toSet(), expected.keys, "jede Art hat ein festgelegtes Ziel")
        for ((kind, target) in expected) assertEquals(target, PushMessage(kind, MATCH, ALEX).target, kind.name)
        assertEquals(PushChannel.SCORING, PushKind.SCORER_RESPONDED.channel)
        assertEquals(PushChannel.MATCHES, PushKind.VISITOR_INVITED.channel)
    }
}

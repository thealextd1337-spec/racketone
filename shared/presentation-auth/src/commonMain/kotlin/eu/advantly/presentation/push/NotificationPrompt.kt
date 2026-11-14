package eu.advantly.presentation.push

import eu.advantly.presentation.friends.FriendsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Erklärseite zur Benachrichtigungs-Berechtigung im passenden Moment (Plan W12, Paket 2, Entscheidungen 2–4).

/** Passender Moment: Es gibt Freunde, offene Anfragen (ein- oder ausgehend) oder ein laufendes Match im Bereich „Live“. */
fun notificationMoment(state: FriendsState): Boolean =
    state.friends.isNotEmpty() || state.incoming.isNotEmpty() || state.outgoing.isNotEmpty() || !state.live.isNullOrEmpty()

/** Stand der Erklärseite; [LATER] trägt seinen Zeitpunkt in [NotificationPromptStore.laterAtMs]. */
enum class PromptStage { NEW, LATER, DENIED, DONE }

/** Gerätelokal (nicht im Backup). */
interface NotificationPromptStore {
    var stage: PromptStage
    var laterAtMs: Long

    /** Hinweis im Freunde-Tab zuletzt ausgeblendet; `0` = nie. */
    var hintHiddenAtMs: Long
}

/** Erklärseite für die UI; „Einschalten“ fragt die Plattform-Berechtigung ab. */
interface NotificationPromptPort {
    val prompt: StateFlow<Boolean>

    /** Der Freunde-Stand erfüllt [notificationMoment]. */
    fun onMoment()

    fun accept()

    /** „Später“, auch Zurück. */
    fun later()
}

const val PROMPT_AGAIN_MS = 14L * 24 * 60 * 60 * 1000
const val HINT_HIDDEN_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Zustandsmaschine: [PromptStage.NEW] zeigt die Seite beim nächsten Moment; „Später“ einmal → [PromptStage.LATER], frühestens
 * nach [PROMPT_AGAIN_MS] noch einmal, zweites „Später“ → [PromptStage.DONE]. Abgelehnte Berechtigung → [PromptStage.DENIED].
 * In [PromptStage.DONE] und [PromptStage.DENIED] erscheint die Seite nie wieder automatisch, es bleibt der Hinweis.
 */
class NotificationPrompt(
    private val store: NotificationPromptStore,
    private val now: () -> Long,
) {
    private val mutablePrompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = mutablePrompt.asStateFlow()

    /** [displayOn]: Benachrichtigungen werden schon angezeigt – dann nie. */
    fun onMoment(displayOn: Boolean) {
        if (displayOn) return
        val stage = store.stage
        if (stage == PromptStage.NEW || (stage == PromptStage.LATER && now() - store.laterAtMs >= PROMPT_AGAIN_MS)) {
            mutablePrompt.value = true
        }
    }

    fun later() {
        mutablePrompt.value = false
        when (store.stage) {
            PromptStage.NEW -> {
                store.laterAtMs = now()
                store.stage = PromptStage.LATER
            }

            PromptStage.LATER -> {
                store.stage = PromptStage.DONE
            }

            PromptStage.DENIED, PromptStage.DONE -> {
                Unit
            }
        }
    }

    /** Eingeschaltet (Erklärseite oder Profil) oder im Profil ausdrücklich ausgeschaltet. */
    fun decided() {
        mutablePrompt.value = false
        if (store.stage != PromptStage.DENIED) store.stage = PromptStage.DONE
    }

    /** Systemdialog nach „Einschalten“ auf der Erklärseite abgelehnt. */
    fun denied() {
        mutablePrompt.value = false
        store.stage = PromptStage.DENIED
    }

    fun hintVisible(displayOn: Boolean): Boolean =
        !displayOn && (store.hintHiddenAtMs == 0L || now() - store.hintHiddenAtMs >= HINT_HIDDEN_MS)

    fun hideHint() {
        store.hintHiddenAtMs = now()
    }
}

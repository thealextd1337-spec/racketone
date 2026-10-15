import AdvantlyWatchKit
import WatchKit

/// Haptik-Muster nach 04 §5.8 (Wir und Gegner unterscheidbar, Haptik ersetzt nie den visuellen Zustand).
enum Haptics {
    static func play(_ type: WKHapticType) {
        WKInterfaceDevice.current().play(type)
    }

    static func after(_ seconds: Double, _ type: WKHapticType) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { play(type) }
    }

    /// Gesperrt-Tap / Fehler
    static func rejected() { play(.failure) }

    static func undo() { play(.directionDown) }

    /// Rückmeldung nach einem Punkt: Wir = 1 Klick, Gegner = 2 Klicks; Game/Satz/Match eigene Muster;
    /// neuer Break-/Satz-/Matchball = Aufmerksamkeits-Impuls zu Beginn des nächsten Punktes.
    static func feedback(after snap: WatchScoreSnapshot, us: Bool, previousHint: String) {
        let effects = Set(snap.effects)
        if effects.contains("SET_WON") || effects.contains("MATCH_WON") || effects.contains("MATCH_DRAWN") {
            if us {
                play(.success)
                after(0.35, .notification)
            } else {
                play(.directionDown)
            }
        } else if effects.contains("GAME_WON") {
            play(us ? .success : .directionDown)
        } else if us {
            play(.click)
        } else {
            play(.click)
            after(0.12, .click)
        }
        let attention: Set<String> = ["BREAK_POINT", "SET_POINT", "MATCH_POINT"]
        // Seitenwechsel (`.start`) meldet ScoringModel.afterCommand für alle Befehle
        if attention.contains(snap.hintCode) && snap.hintCode != previousHint {
            after(1.0, .notification)
        }
    }
}

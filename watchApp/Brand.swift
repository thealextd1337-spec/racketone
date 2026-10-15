import SwiftUI

/// Farb-Tokens aus 04 §5.2 (Dark) und Sport-Stil (Icon + Farbe, nie Farbe allein).
enum Brand {
    /// Team „Wir“ (Markenfarbe Clay)
    static let us = Color(hex: 0xFF6A1A)
    /// Team „Gegner“
    static let them = Color(hex: 0x5B9BFF)
    /// Hinweis-Pill (Break-/Satz-/Matchball)
    static let hint = Color(hex: 0xFFB020)
    static let text2 = Color(hex: 0x9AA1AD)

    static func team(sideA: Bool) -> Color { sideA ? us : them }
}

struct SportStyle {
    let icon: String
    let color: Color

    /// `sport` ist der Code aus Kotlin (`TENNIS`, `PADEL`, `PICKLEBALL`, `BADMINTON`, `TABLE_TENNIS`).
    static func of(_ sport: String) -> SportStyle {
        switch sport {
        case "PADEL": return SportStyle(icon: "figure.racquetball", color: Color(hex: 0x22C3E6))
        case "PICKLEBALL": return SportStyle(icon: "figure.pickleball", color: Color(hex: 0xFF5FA2))
        case "BADMINTON": return SportStyle(icon: "figure.badminton", color: Color(hex: 0xA78BFA))
        case "TABLE_TENNIS": return SportStyle(icon: "figure.table.tennis", color: Color(hex: 0x34D399))
        default: return SportStyle(icon: "tennisball.fill", color: Color(hex: 0xC8E62E))
        }
    }

    static func isRally(_ sport: String) -> Bool { sport == "BADMINTON" || sport == "TABLE_TENNIS" }
    static func isTennisLike(_ sport: String) -> Bool { sport == "TENNIS" || sport == "PADEL" }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Lokalisierter Text für zusammengesetzte Schlüssel (Codes aus Kotlin → Localizable.strings).
func loc(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

func loc(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}

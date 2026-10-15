import SwiftUI

/// Swift-Host der iPhone-App. Die eigentliche UI ist Compose Multiplatform aus `AdvantlyKit`, die Match-Session
/// (WatchConnectivity zur Apple Watch) startet `AdvantlyKitKt.mainViewController()`.
/// Kein eigener Link-Debug-Tab mehr: WCSession hat nur einen Delegate, ein zweiter Link würde die Session stören (W1-I).
@main
struct AdvantlyApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

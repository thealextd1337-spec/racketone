import SwiftUI

/// Swift-Host der iPhone-App. Die eigentliche UI ist Compose Multiplatform aus `AdvantlyKit`.
/// In DEBUG-Builds gibt es zusätzlich einen Tab für den Link-Debug-Feldtest (Spike S3).
@main
struct AdvantlyApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            TabView {
                ComposeView()
                    .ignoresSafeArea()
                    .tabItem { Label("tab.app", systemImage: "sportscourt") }
                LinkDebugView()
                    .tabItem { Label("tab.linkdebug", systemImage: "applewatch.radiowaves.left.and.right") }
            }
            #else
            ComposeView()
                .ignoresSafeArea()
            #endif
        }
    }
}

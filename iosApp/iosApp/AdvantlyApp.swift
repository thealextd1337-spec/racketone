import SwiftUI

/// Swift-Host der iPhone-App. Die eigentliche UI ist Compose Multiplatform aus `AdvantlyKit`.
@main
struct AdvantlyApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

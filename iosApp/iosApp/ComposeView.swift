import AdvantlyKit
import SwiftUI
import UIKit

/// Bettet den `ComposeUIViewController` aus `AdvantlyKit` in SwiftUI ein.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        AdvantlyKitKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

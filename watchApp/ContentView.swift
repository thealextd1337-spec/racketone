import AdvantlyWatchKit
import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 8) {
            // Lokalisiert über en.lproj / de.lproj (Localizable.strings)
            Text("watch.greeting")
                .font(.headline)
            Text("watch.engine \(AdvantlyWatch.shared.engineModule())")
                .font(.footnote)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

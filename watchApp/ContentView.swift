import AdvantlyWatchKit
import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("Advantly")
                .font(.headline)
            Text(AdvantlyWatch.shared.hello())
                .font(.footnote)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

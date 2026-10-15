import AdvantlyWatchKit
import SwiftUI

struct ContentView: View {
    /// Ergebnis des Store-Smoke-Tests beim Start (Spike S1): erwartet immer 1.
    @State private var storeCount: Int64 = -1

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 8) {
                    // Lokalisiert über en.lproj / de.lproj (Localizable.strings)
                    Text("watch.greeting")
                        .font(.headline)
                    Text("watch.engine \(AdvantlyWatch.shared.engineModule())")
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                    Text("watch.store \(String(storeCount))")
                        .font(.footnote)
                    #if DEBUG
                    NavigationLink("link.debug.open") { LinkDebugView() }
                    #endif
                }
                .padding()
            }
        }
        .onAppear(perform: runStoreSmoke)
    }

    /// Öffnet den SQLDelight-Store (native-driver, Datei advantly.db) und schreibt idempotent ein Smoke-Event.
    /// Die CI startet die App im Simulator und sucht die Zeile `ADVANTLY_SMOKE` in der Konsole.
    private func runStoreSmoke() {
        let count = AdvantlyWatch.shared.store().smokeCheck()
        storeCount = count
        print("ADVANTLY_SMOKE eventstore=ok count=\(count)")
        fflush(stdout)
    }
}

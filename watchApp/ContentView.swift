import AdvantlyWatchKit
import SwiftUI

struct ContentView: View {
    #if DEBUG
    /// Ergebnis des Store-Smoke-Tests (Spike S1), -1 = nicht gelaufen.
    @State private var storeCount: Int64 = -1
    #endif

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
                    #if DEBUG
                    if storeCount >= 0 {
                        Text("watch.store \(String(storeCount))")
                            .font(.footnote)
                    }
                    NavigationLink("link.debug.open") { LinkDebugView() }
                    #endif
                }
                .padding()
            }
        }
        #if DEBUG
        .onAppear(perform: runStoreSmokeIfRequested)
        #endif
    }

    #if DEBUG
    /// Nur Debug-Build UND Startparameter `-advantlySmoke` (setzt die CI beim `simctl launch`): öffnet den
    /// SQLDelight-Store (native-driver, Datei advantly.db), schreibt idempotent ein Smoke-Event und druckt die
    /// Zeile `ADVANTLY_SMOKE`, die die CI in der Konsole sucht. Release-Builds und normale Starts schreiben nichts.
    private func runStoreSmokeIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-advantlySmoke") else { return }
        let count = AdvantlyWatch.shared.store().smokeCheck()
        storeCount = count
        print("ADVANTLY_SMOKE eventstore=ok count=\(count)")
        fflush(stdout)
    }
    #endif
}

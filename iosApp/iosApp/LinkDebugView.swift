import AdvantlyKit
import SwiftUI

#if DEBUG
/// Ping-Pong-Feldtest iPhone <-> Apple Watch über WatchConnectivity (Spike S3, nur DEBUG).
/// Logik und Zahlen kommen aus Kotlin (`LinkDebug` in AdvantlyKit); Swift zeigt nur an.
struct LinkDebugView: View {
    @StateObject private var model = LinkDebugModel()

    var body: some View {
        List {
            if let s = model.snapshot {
                // Schlüssel als String bauen – ein interpolierter Literal würde zum Schlüssel "link.state.%@"
                Text(LocalizedStringKey("link.state." + s.state)).font(.headline)
                HStack {
                    Button("link.send10") { model.ping(10) }
                    Button("link.send100") { model.ping(100) }
                }
                .buttonStyle(.borderedProminent)
                HStack {
                    Button("link.stop") { model.debug.stopPings() }
                    Button("link.reset") { model.debug.reset() }
                }
                .buttonStyle(.bordered)
                if model.noPeer { Text("link.nopeer").foregroundStyle(.orange) }
                Text("link.sent \(String(s.pingsSent))")
                Text("link.pongs \(String(s.pongsReceived))")
                Text("link.answered \(String(s.pingsAnswered))")
                Text("link.queued \(String(s.queued))")
                Text("link.lost \(String(s.lost))")
                Text("link.retries \(String(s.retries))")
                Text("link.rtt \(ms(s.rttP50Ms)) \(ms(s.rttP95Ms))")
            }
        }
        .onAppear { model.start() }
    }

    private func ms(_ value: Int64) -> String { value < 0 ? "–" : "\(value) ms" }
}

final class LinkDebugModel: NSObject, ObservableObject, LinkDebugListener {
    let debug = AdvantlyKitKt.linkDebug()
    @Published var snapshot: LinkDebugSnapshot?
    @Published var noPeer = false
    private var started = false

    func start() {
        // WCSession hat nur einen Delegate → Session einmal je Prozess starten
        guard !started else { return }
        started = true
        debug.start(listener: self)
    }

    func ping(_ count: Int32) { noPeer = !debug.sendPings(count: count, intervalMs: 250) }

    func onUpdate(snapshot: LinkDebugSnapshot) { self.snapshot = snapshot }
}
#endif

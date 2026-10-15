import AdvantlyWatchKit
import SwiftUI

/// Ping-Pong-Feldtest Apple Watch <-> iPhone über WatchConnectivity (Spike S3, nur DEBUG).
/// Logik und Zahlen kommen aus Kotlin (`LinkDebug`); Swift zeigt nur an.
struct LinkDebugView: View {
    @StateObject private var model = LinkDebugModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if let s = model.snapshot {
                    // Schlüssel als String bauen – ein interpolierter Literal würde zum Schlüssel "link.state.%@"
                    Text(LocalizedStringKey("link.state." + s.state)).font(.headline)
                    HStack {
                        Button("link.send10") { model.ping(10) }
                        Button("link.stop") { model.debug.stopPings() }
                    }
                    Button("link.reset") { model.debug.reset() }
                    Text("link.sent \(String(s.pingsSent))")
                    Text("link.pongs \(String(s.pongsReceived))")
                    Text("link.answered \(String(s.pingsAnswered))")
                    Text("link.queued \(String(s.queued))")
                    Text("link.lost \(String(s.lost))")
                    Text("link.retries \(String(s.retries))")
                    Text("link.rtt \(ms(s.rttP50Ms)) \(ms(s.rttP95Ms))")
                    if model.noPeer { Text("link.nopeer").foregroundStyle(.orange) }
                }
            }
            .font(.footnote)
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    private func ms(_ value: Int64) -> String { value < 0 ? "–" : "\(value) ms" }
}

final class LinkDebugModel: NSObject, ObservableObject, LinkDebugListener {
    let debug = AdvantlyWatch.shared.linkDebug()
    @Published var snapshot: LinkDebugSnapshot?
    @Published var noPeer = false

    func start() { debug.start(listener: self) }
    func stop() { debug.stop() }
    func ping(_ count: Int32) { noPeer = !debug.sendPings(count: count, intervalMs: 250) }

    func onUpdate(snapshot: LinkDebugSnapshot) { self.snapshot = snapshot }
}

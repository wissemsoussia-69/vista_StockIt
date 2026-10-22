import SwiftUI

/// Bandeau discret affiché quand le mode test est actif.
struct TestModeBanner: View {
    @AppStorage("testMode") private var on = false
    static var isOn: Bool { UserDefaults.standard.bool(forKey: "testMode") }
    var body: some View {
        if on {
            Text("🧪 MODE TEST — aucune action Jira / Slack / e-mail réelle")
                .font(.caption2.bold()).foregroundColor(.vistaNavy)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color.vistaAmber, in: Capsule())
                .padding(.top, 2)
                .allowsHitTesting(false)
        }
    }
}

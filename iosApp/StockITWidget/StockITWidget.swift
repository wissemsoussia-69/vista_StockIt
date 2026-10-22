import WidgetKit
import SwiftUI

/// Widget écran d'accueil (StockWidget Android) — lit les chiffres partagés par l'app (App Group).
struct StockEntry: TimelineEntry {
    let date: Date
    let total: Int
    let low: Int
    let out: Int
}

struct StockProvider: TimelineProvider {
    private func read() -> StockEntry {
        let ud = UserDefaults(suiteName: "group.com.ETX.stockit")
        return StockEntry(date: Date(), total: ud?.integer(forKey: "widget_total") ?? 0,
                          low: ud?.integer(forKey: "widget_low") ?? 0, out: ud?.integer(forKey: "widget_out") ?? 0)
    }
    func placeholder(in context: Context) -> StockEntry { StockEntry(date: Date(), total: 45, low: 2, out: 1) }
    func getSnapshot(in context: Context, completion: @escaping (StockEntry) -> Void) { completion(read()) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<StockEntry>) -> Void) {
        completion(Timeline(entries: [read()], policy: .after(Date().addingTimeInterval(24 * 3600))))
    }
}

/// Langue choisie dans l'app (partagée via l'App Group).
private var widgetIsEnglish: Bool {
    let ud = UserDefaults(suiteName: "group.com.ETX.stockit")
    if let l = ud?.string(forKey: "appLanguage") { return l == "en" }
    return Locale.preferredLanguages.first?.hasPrefix("en") == true
}

struct StockWidgetView: View {
    let entry: StockEntry
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("STOCKIT").font(.caption.bold()).opacity(0.8)
            Text("\(entry.total)").font(.system(size: 36, weight: .bold))
            Text(verbatim: widgetIsEnglish ? "items in stock" : "articles en stock").font(.caption)
            Spacer()
            if entry.out + entry.low > 0 {
                Text(verbatim: widgetIsEnglish ? "⚠️ \(entry.out) out · \(entry.low) low" : "⚠️ \(entry.out) rupture · \(entry.low) bas").font(.caption2.bold())
            }
        }
        .foregroundColor(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .modifier(WidgetBackground())
    }
}

private struct WidgetBackground: ViewModifier {
    private let gradient = LinearGradient(colors: [Color(red: 0.06, green: 0.33, blue: 0.98), Color(red: 0, green: 0.2, blue: 0.63)],
                                          startPoint: .topLeading, endPoint: .bottomTrailing)
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.containerBackground(gradient, for: .widget)
        } else {
            content.padding().background(gradient)
        }
    }
}

@main
struct StockITHomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "StockITWidget", provider: StockProvider()) { StockWidgetView(entry: $0) }
            .configurationDisplayName("StockIT")
            .description(widgetIsEnglish ? "Items in stock and alerts." : "Articles en stock et alertes.")
            .supportedFamilies([.systemSmall, .systemMedium])
    }
}

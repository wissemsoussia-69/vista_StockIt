import SwiftUI
import Charts
import Shared

/// MainActivity : barre d'onglets (Dashboard / Stock / Scanner / Tickets / Plus).
/// Le bouton central remplace le FAB Vista (Scanner asset, Réception colis, Sortie, Rafale).
struct MainTabView: View {
    @EnvironmentObject var app: AppState
    @State private var tab = 0
    @State private var morePath: [String] = []
    @State private var scanMenu = false
    @State private var flow: ScanFlow?

    enum ScanFlow: String, Identifiable { case asset, receive, out, batch; var id: String { rawValue } }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { DashboardView(openScan: { scanMenu = true }, goStock: { tab = 1 }) }
                .tabItem { Label("Dashboard", systemImage: "square.grid.2x2.fill") }.tag(0)
            NavigationStack { StockListView() }
                .tabItem { Label("Stock", systemImage: "shippingbox.fill") }.tag(1)
            Color.clear
                .tabItem { Label("Scanner", systemImage: "viewfinder.circle.fill") }.tag(2)
            NavigationStack { TicketBrowserView() }
                .tabItem { Label("Tickets", systemImage: "ticket.fill") }.tag(3)
            NavigationStack(path: $morePath) { MoreView().navigationDestination(for: String.self) { _ in SettingsView() } }
                .tabItem { Label("Plus", systemImage: "line.3.horizontal") }.tag(4)
        }
        .onAppear {
            // Après un changement de langue, l'interface est reconstruite : on revient sur Paramètres.
            if app.reopenSettings {
                app.reopenSettings = false
                tab = 4
                morePath = ["settings"]
            }
        }
        .onChange(of: tab) { new in
            if new == 2 { scanMenu = true; tab = 0 }
            Haptics.tap()
        }
        .confirmationDialog("Actions caméra", isPresented: $scanMenu, titleVisibility: .visible) {
            Button("🧠 Scanner un équipement (IA)") { flow = .asset }
            Button("📦 Réception colis (facture)") { flow = .receive }
            Button("📤 Sortie équipement") { flow = .out }
            Button("🔢 Scan codes-barres en rafale") { flow = .batch }
            Button("Annuler", role: .cancel) {}
        }
        .fullScreenCover(item: $flow) { f in
            switch f {
            case .asset: ScanAssetView()
            case .receive: ReceivePackageView(mode: .standalone)
            case .out: ScanOutView()
            case .batch: BatchScanView()
            }
        }
    }
}

struct DashboardView: View {
    @EnvironmentObject var app: AppState
    let openScan: () -> Void
    let goStock: () -> Void
    @State private var report: ReportData?
    @State private var quest: Quest?
    @State private var movements: [StockMovement] = []
    @State private var showChat = false

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 12 ? L("Bonjour") : h < 18 ? L("Bon après-midi") : L("Bonsoir")
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                hero
                HStack(spacing: 12) {
                    kpi("Quantité Totale", "\(report?.totalQuantity ?? 0)", "cube.box.fill", .vistaBlue)
                    kpi("Valeur Actuelle", (report?.totalValue ?? 0).money, "eurosign.circle.fill", .vistaMint)
                }
                alerts
                quickActions
                if let q = quest { questCard(q) }
                recent
            }
            .padding()
        }
        .background(Color.vistaBg.ignoresSafeArea())
        .navigationTitle("Tableau de bord")
        .refreshable { await load() }
        .task(id: app.dataVersion) { await load() }
        .sheet(isPresented: $showChat) { NavigationStack { AIChatView() } }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading) {
                    Text("\(greeting) \(app.user?.username ?? "Technician") 👋").font(.title2.bold())
                    Text("\(report?.totalQuantity ?? 0) articles · \(report?.outOfStockCount ?? 0) en rupture")
                        .font(.subheadline).opacity(0.85)
                }
                Spacer()
                Text(String((app.user?.username ?? "T").prefix(1)).uppercased())
                    .font(.title2.bold()).frame(width: 48, height: 48)
                    .background(Color.white.opacity(0.2), in: Circle())
            }
            Chart(Array(sparkline.enumerated()), id: \.offset) { i, v in
                AreaMark(x: .value("i", i), y: .value("v", v))
                    .foregroundStyle(Color.vistaTurquoise.opacity(0.3)).interpolationMethod(.catmullRom)
                LineMark(x: .value("i", i), y: .value("v", v))
                    .foregroundStyle(Color.vistaTurquoise).interpolationMethod(.catmullRom)
            }
            .chartXAxis(.hidden).chartYAxis(.hidden).frame(height: 50)
        }
        .foregroundColor(.white)
        .padding(18)
        .background(LinearGradient(colors: [.vistaBlue, .vistaBlueDeep], startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 22))
    }

    private var sparkline: [Double] {
        let n = min(7, movements.count)
        if n < 2 { return [2, 3.2, 2.8, 4.1, 3.6, 5.0, 4.7] }
        return movements.prefix(n).reversed().map { Double(abs($0.quantity)) }
    }

    private func kpi(_ t: LocalizedStringKey, _ v: String, _ icon: String, _ c: Color) -> some View {
        VistaCard {
            Image(systemName: icon).foregroundColor(c).font(.title2)
            Text(v).font(.title3.bold()).lineLimit(1).minimumScaleFactor(0.6)
            Text(t).font(.caption).foregroundColor(.secondary)
        }
    }

    private var alerts: some View {
        VistaCard {
            HStack {
                Circle().fill(Color.vistaCoral).frame(width: 10, height: 10)
                Text("🔔 Alertes Urgentes").font(.headline)
            }
            let out = report?.outOfStockCount ?? 0, low = report?.lowStockCount ?? 0
            Text(verbatim: out + low == 0 ? L("Tout est sous contrôle.") : L("%@ en rupture, %@ bas", String(out), String(low)))
                .foregroundColor(out + low == 0 ? .secondary : .vistaCoral)
        }
    }

    private var quickActions: some View {
        VistaCard {
            Text("Actions Rapides").font(.headline)
            HStack(spacing: 10) {
                action("Scan", "viewfinder", openScan)
                action("Stock", "shippingbox", goStock)
                action("Assistant IA", "sparkles") { showChat = true }
            }
        }
    }

    private func action(_ t: LocalizedStringKey, _ i: String, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            VStack(spacing: 6) {
                Image(systemName: i).font(.title2)
                Text(t).font(.caption.bold())
            }
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .background(Color.vistaBlue.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func questCard(_ q: Quest) -> some View {
        VistaCard {
            Text(verbatim: "🏆 " + LV(q.title)).font(.headline)
            Text(verbatim: "\(LV(q.description_)) (\(q.currentCount)/\(q.goalCount))").font(.subheadline).foregroundColor(.secondary)
            ProgressView(value: Double(q.currentCount), total: Double(max(q.goalCount, 1)))
        }
    }

    private var recent: some View {
        VistaCard {
            HStack {
                Text("Derniers Mouvements").font(.headline)
                Spacer()
                Button("Voir tout", action: goStock).font(.caption)
            }
            if movements.isEmpty { Text("Aucun mouvement").foregroundColor(.secondary) }
            ForEach(movements.prefix(3)) { MovementRow(m: $0) }
        }
    }

    private func load() async {
        report = try? await app.repo.getReportData()
        quest = (try? await app.repo.getQuests())?.first
        movements = (try? await app.repo.getStockMovements()) ?? []
    }
}

struct MovementRow: View {
    let m: StockMovement
    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: m.type == "IN" ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                .foregroundColor(m.type == "IN" ? .vistaMint : .vistaCoral)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(m.productName ?? "?")  ·  \(m.type == "IN" ? "+" : "-")\(m.quantity)").font(.subheadline.bold())
                Text("Motif: \(LV(m.reason ?? "-"))").font(.caption).foregroundColor(.secondary)
                Text("Par: \(LV(m.userName ?? "-")) · \(m.date ?? "")").font(.caption2).foregroundColor(.secondary)
                if let c = m.comment, !c.isEmpty { Text("Com: \(LV(c))").font(.caption2).foregroundColor(.secondary) }
            }
        }
    }
}

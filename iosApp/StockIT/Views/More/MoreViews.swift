import SwiftUI
import Charts
import Shared

/// Équivalent du tiroir de navigation Android.
struct MoreView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        List {
            Section {
                NavigationLink { ProfileView() } label: {
                    HStack {
                        Text(String((app.user?.username ?? "?").prefix(1)).uppercased())
                            .font(.title3.bold()).foregroundColor(.white)
                            .frame(width: 44, height: 44).background(Color.vistaBlue, in: Circle())
                        VStack(alignment: .leading) {
                            Text(app.user?.username ?? "").font(.headline)
                            Text(L("Gestionnaire d'inventaire · %@", String(describing: app.user?.role ?? ""))).font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            }
            Section("Catalogue") {
                NavigationLink { CategoriesView() } label: { Label("Catégories", systemImage: "tag") }
                NavigationLink { SuppliersView() } label: { Label("Fournisseurs", systemImage: "building.2") }
                NavigationLink { PurchaseOrdersView() } label: { Label("Commandes", systemImage: "cart") }
                NavigationLink { ShippingView() } label: { Label("Expéditions", systemImage: "truck.box") }
            }
            Section("Intelligence") {
                NavigationLink { ReportsView() } label: { Label("Rapports", systemImage: "chart.pie") }
                NavigationLink { AIInsightsView() } label: { Label("IA Optimisation", systemImage: "brain") }
                NavigationLink { ForecastView() } label: { Label("Prévision des besoins", systemImage: "chart.line.uptrend.xyaxis") }
                NavigationLink { AIQualityView() } label: { Label("Qualité de l'IA", systemImage: "gauge.with.dots.needle.67percent") }
                NavigationLink { AIChatView() } label: { Label("Assistant IA Vista", systemImage: "sparkles") }
                NavigationLink { LeaderboardView() } label: { Label("Classement / Leaderboard", systemImage: "trophy") }
                NavigationLink { AuditView() } label: { Label("Audit Actions", systemImage: "list.bullet.clipboard") }
            }
            Section("Administration") {
                NavigationLink { UsersView() } label: { Label("Utilisateurs", systemImage: "person.3") }
                NavigationLink { ClaimsView() } label: { Label("Réclamation", systemImage: "exclamationmark.bubble") }
                NavigationLink { MonthlyReportView() } label: { Label("Rapport mensuel IA (PDF)", systemImage: "doc.richtext") }
            }
            Section {
                NavigationLink(value: "settings") { Label("Paramètres", systemImage: "gearshape") }
                Button(role: .destructive) { Task { await app.logout() } } label: {
                    Label("Déconnexion", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }
        }
        .navigationTitle("Plus")
    }
}

// MARK: - Profil / Paramètres

struct ProfileView: View {
    @EnvironmentObject var app: AppState
    var body: some View {
        List {
            Section("Statistiques Gamification") {
                LabeledContent("Niveau", value: L("NIVEAU %@", String(describing: app.user?.level ?? 1)))
                LabeledContent("Points", value: L("%@ POINTS", String(describing: app.user?.points ?? 0)))
                LabeledContent("Badges", value: (app.user?.badges.isEmpty ?? true) ? L("Aucun") : app.user!.badges.split(separator: ",").map { LV(String($0)) }.joined(separator: ", "))
            }
            Section("Informations personnelles") {
                LabeledContent("Utilisateur", value: app.user?.username ?? "")
                LabeledContent("E-mail", value: app.session.email ?? "\(app.user?.username ?? "")@vistaprint.com")
                LabeledContent("Rôle", value: app.user?.role ?? "")
                LabeledContent("Département", value: L("IT Infrastructure"))
                LabeledContent("Localisation", value: L("Bureau Central"))
                LabeledContent("Connexion", value: app.session.provider == "auth0" ? L("Vista SSO (Auth0)") : L("Locale (démo)"))
            }
            Section {
                Button("Se déconnecter", role: .destructive) { Task { await app.logout() } }
            }
        }
        .navigationTitle("Mon Profil")
        .onAppear { app.refreshUser() }
    }
}

struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @AppStorage("darkMode") private var darkMode = false
    @AppStorage("testMode") private var testMode = false
    @AppStorage(AppLanguage.storageKey) private var language = AppLanguage.current.rawValue
    @State private var pending: Int64 = 0
    @State private var slackTesting = false
    @State private var slackResult: String?
    var body: some View {
        Form {
            Section("Préférences") {
                Toggle("Mode sombre", isOn: $darkMode)
                Picker("Langue", selection: $language) {
                    ForEach(AppLanguage.allCases) { l in
                        Text(verbatim: l.displayName).tag(l.rawValue)
                    }
                }
                .accessibilityIdentifier("languagePicker")
            }
            Section {
                Toggle("🧪 Mode test", isOn: $testMode)
                    .onChange(of: testMode) { v in StockItConfig.shared.testMode = v }
                    .accessibilityIdentifier("testModeToggle")
            } footer: {
                Text("Aucun ticket Jira créé, aucun message Slack, aucun e-mail ni webhook n8n. Claude et la lecture des tickets restent actifs. Les changements de stock restent enregistrés sur le téléphone.")
            }
            Section("Intégrations") {
                status("Cimpress Gateway (IA)", StockItConfig.shared.isCimpressConfigured)
                status("Claude direct (IA)", StockItConfig.shared.isClaudeConfigured)
                if !StockItConfig.shared.isAIConfigured {
                    Text("Mode hors ligne actif : Apple Vision + analyse locale du stock.").font(.caption).foregroundColor(.secondary)
                }
                status("Jira", StockItConfig.shared.isJiraConfigured)
                status("Jira Assets (CMDB \(StockItConfig.shared.jiraAssetsSchemaId))", StockItConfig.shared.isJiraAssetsConfigured)
                status("Slack", !StockItConfig.shared.stockitSlackWebhookUrl.isEmpty || !StockItConfig.shared.slackWebhookUrl.isEmpty || !StockItConfig.shared.slackBotToken.isEmpty)
                HStack {
                    Button("Tester Slack") {
                        slackTesting = true; slackResult = nil
                        Task {
                            let r = try? await SlackNotifier.shared.sendTest(user: app.user?.username)
                            slackResult = (r?.success ?? false) ? L("✅ Message envoyé dans #etx-alerts") : "❌ " + (r?.detail ?? L("erreur réseau"))
                            slackTesting = false
                        }
                    }
                    .disabled(slackTesting)
                    .accessibilityIdentifier("testSlack")
                    Spacer()
                    if slackTesting { ProgressView() }
                }
                if let r = slackResult {
                    Text(r).font(.caption).foregroundColor(r.hasPrefix("✅") ? .green : .vistaCoral)
                        .accessibilityIdentifier("slackResult")
                }
                status("n8n — e-mails (send)", !StockItConfig.shared.stockitWebhookUrl.isEmpty)
                status("n8n — mouvements (event)", !StockItConfig.shared.stockitEventWebhookUrl.isEmpty)
                status("n8n — statut actifs (asset-status)", !StockItConfig.shared.stockitAssetStatusWebhookUrl.isEmpty)
                status("Vista SSO (Auth0)", AuthService.shared.isConfigured)
            }
            Section("Automatisations en attente") {
                LabeledContent("Envois n8n en file d'attente", value: "\(pending)")
                Button("Renvoyer maintenant") {
                    Task { pending = (try? await app.repo.flushOutbox())?.int64Value ?? pending }
                }
            }
            Section { Text("Version de l'application : \(AppConfig.version)").foregroundColor(.secondary) }
        }
        .navigationTitle("Paramètres")
        .task { pending = (try? await app.repo.pendingWebhookCount())?.int64Value ?? 0 }
    }
    private func status(_ t: LocalizedStringKey, _ ok: Bool) -> some View {
        LabeledContent(t) { Text(ok ? L("✅ Configuré") : L("⚠️ Non configuré")).foregroundColor(ok ? .green : .orange) }
    }
}

// MARK: - Catégories

struct CategoriesView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [ProductCategory] = []
    @State private var editing: ProductCategory?
    @State private var adding = false
    @State private var name = ""
    @State private var color = "#0F53FA"

    var body: some View {
        List {
            ForEach(items) { c in
                HStack {
                    Circle().fill(Color(hexString: c.labelColor) ?? .gray).frame(width: 14, height: 14)
                    Text(verbatim: LV(c.name))
                }
                .contentShape(Rectangle())
                .onTapGesture { if app.isAdmin { editing = c; name = c.name; color = c.labelColor ?? "" } }
            }
            .onDelete { idx in
                let del = idx.map { items[$0] }
                Task { for c in del { _ = try? await app.repo.deleteCategory(c: c) }; await load() }
            }
            .deleteDisabled(!app.isAdmin)
        }
        .navigationTitle("Catégories")
        .toolbar { if app.isAdmin { Button { name = ""; color = "#0F53FA"; adding = true } label: { Image(systemName: "plus") } } }
        .task { await load() }
        .alert(editing == nil ? L("Nouvelle catégorie") : L("Modifier"), isPresented: Binding(get: { adding || editing != nil },
                                                                                       set: { if !$0 { adding = false; editing = nil } })) {
            TextField("Nom", text: $name)
            TextField("Couleur (#RRGGBB)", text: $color)
            Button("Enregistrer") {
                let e = editing
                Task {
                    if let e { _ = try? await app.repo.updateCategory(c: e.withEdits(name: name, labelColor: color)) }
                    else if !name.isEmpty { _ = try? await app.repo.addCategory(name: name, parentId: nil, color: color) }
                    await load()
                }
            }
            Button("Annuler", role: .cancel) {}
        }
    }
    private func load() async { items = (try? await app.repo.getCategories()) ?? [] }
}

// MARK: - Fournisseurs

struct SuppliersView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [Supplier] = []
    @State private var form: Supplier??

    var body: some View {
        List {
            ForEach(items) { s in
                VStack(alignment: .leading, spacing: 3) {
                    Text(s.name).font(.headline)
                    Text([s.email, s.phone, s.address].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundColor(.secondary)
                    Text(L("Délai : %@ j · Remise : %@ %%", String(describing: s.leadTime), String(describing: Int(s.discount)))).font(.caption2)
                }
                .contentShape(Rectangle())
                .onTapGesture { if app.isAdmin { form = .some(s) } }
            }
            .onDelete { idx in
                let del = idx.map { items[$0] }
                Task { for s in del { _ = try? await app.repo.deleteSupplier(s: s) }; await load() }
            }
            .deleteDisabled(!app.isAdmin)
        }
        .navigationTitle("Fournisseurs")
        .toolbar { if app.isAdmin { Button { form = .some(nil) } label: { Image(systemName: "plus") } } }
        .task { await load() }
        .sheet(isPresented: Binding(get: { form != nil }, set: { if !$0 { form = nil } })) {
            NavigationStack { SupplierForm(supplier: form ?? nil) { await load() } }
        }
    }
    private func load() async { items = (try? await app.repo.getSuppliers()) ?? [] }
}

struct SupplierForm: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    let supplier: Supplier?
    let onSaved: () async -> Void
    @State private var name = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var address = ""
    @State private var lead = "5"
    @State private var discount = "0"

    var body: some View {
        Form {
            TextField("Nom", text: $name)
            TextField("E-mail", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never)
            TextField("Téléphone", text: $phone).keyboardType(.phonePad)
            TextField("Adresse", text: $address)
            TextField("Délai de livraison (jours)", text: $lead).keyboardType(.numberPad)
            TextField("Remise (%)", text: $discount).keyboardType(.decimalPad)
        }
        .navigationTitle(supplier == nil ? L("Nouveau fournisseur") : L("Modifier"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Enregistrer") {
                    Task {
                        let l = Int32(lead) ?? 0, d = Double(discount) ?? 0
                        if let s = supplier {
                            _ = try? await app.repo.updateSupplier(s: s.withEdits(name: name, email: email, phone: phone, address: address, leadTime: l, discount: d))
                        } else if !name.isEmpty {
                            _ = try? await app.repo.addSupplier(name: name, email: email, phone: phone, address: address, leadTime: l, discount: d)
                        }
                        await onSaved()
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            if let s = supplier {
                name = s.name; email = s.email ?? ""; phone = s.phone ?? ""; address = s.address ?? ""
                lead = "\(s.leadTime)"; discount = "\(s.discount)"
            }
        }
    }
}

// MARK: - Commandes

struct PurchaseOrdersView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [PurchaseOrder] = []
    @State private var adding = false
    @State private var product = ""
    @State private var qty = "1"
    @State private var supplier = ""

    var body: some View {
        List {
            ForEach(items) { o in
                HStack {
                    VStack(alignment: .leading) {
                        Text("PO#\(o.id) — \(o.productName) x\(o.quantity)").font(.headline)
                        Text("\(o.supplier ?? "-") · \(o.date ?? "")").font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    Pill(text: LV(o.status), color: o.status == "Reçu" ? .vistaMint : .vistaAmber, foreground: .vistaNavy)
                }
                .swipeActions(edge: .leading) {
                    if app.canEditStock && o.status != "Reçu" {
                        Button("Reçu") { Task { _ = try? await app.repo.updatePurchaseOrder(o: o.withStatus(status: "Reçu")); await load() } }
                            .tint(.vistaMint)
                    }
                }
            }
            .onDelete { idx in
                let del = idx.map { items[$0] }
                Task { for o in del { _ = try? await app.repo.deletePurchaseOrder(o: o) }; await load() }
            }
            .deleteDisabled(!app.isAdmin)
        }
        .navigationTitle("Commandes")
        .toolbar { if app.canEditStock { Button { adding = true } label: { Image(systemName: "plus") } } }
        .task(id: app.dataVersion) { await load() }
        .alert("Nouvelle commande", isPresented: $adding) {
            TextField("Produit", text: $product)
            TextField("Quantité", text: $qty).keyboardType(.numberPad)
            TextField("Fournisseur", text: $supplier)
            Button("Créer") {
                Task {
                    if !product.isEmpty { _ = try? await app.repo.createPurchaseOrder(product: product, qty: Int32(qty) ?? 1, supplier: supplier) }
                    product = ""; await load()
                }
            }
            Button("Annuler", role: .cancel) {}
        }
    }
    private func load() async { items = (try? await app.repo.getPurchaseOrders()) ?? [] }
}

// MARK: - Expéditions

struct ShippingView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [ShippingOrder] = []
    @State private var adding = false
    @State private var customer = ""
    @State private var itemsText = ""
    @State private var address = ""
    @State private var ticket = ""
    private let statuses = ["EN ATTENTE", "PENDING", "PREPARING", "SHIPPED", "DELIVERED"]

    var body: some View {
        List {
            ForEach(items) { o in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(o.customerName).font(.headline)
                        Spacer()
                        Menu {
                            ForEach(statuses, id: \.self) { s in
                                Button(LV(s)) { Task { try? await app.repo.updateShippingOrder(o: o.withStatus(status: s, trackingNumber: o.trackingNumber)); await load() } }
                            }
                        } label: { Pill(text: LV(o.status), color: .vistaBlue) }
                    }
                    Text(o.itemsSummary ?? "").font(.subheadline)
                    Text("📍 \(o.shippingAddress ?? "-")  ·  🎫 \(o.ticketNumber ?? "-")  ·  🚚 \(o.trackingNumber ?? "-")")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .onDelete { idx in
                let del = idx.map { items[$0] }
                Task { for o in del { _ = try? await app.repo.deleteShippingOrder(o: o) }; await load() }
            }
            .deleteDisabled(!app.isAdmin)
        }
        .navigationTitle("Expéditions")
        .toolbar { Button { adding = true } label: { Image(systemName: "plus") } }
        .task { await load() }
        .alert("Nouvelle expédition", isPresented: $adding) {
            TextField("Client", text: $customer)
            TextField("Articles (ex: Laptop x2)", text: $itemsText)
            TextField("Adresse", text: $address)
            TextField("N° ticket", text: $ticket)
            Button("Créer") {
                Task {
                    if !customer.isEmpty { try? await app.repo.addShippingOrder(customer: customer, items: itemsText, address: address, ticket: ticket) }
                    customer = ""; await load()
                }
            }
            Button("Annuler", role: .cancel) {}
        }
    }
    private func load() async { items = (try? await app.repo.getShippingOrders()) ?? [] }
}

// MARK: - Rapports / IA / Classement / Audit / Utilisateurs

struct ReportsView: View {
    @EnvironmentObject var app: AppState
    @State private var r: ReportData?
    @State private var csvURL: URL?

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                HStack(spacing: 12) {
                    VistaCard { Text("Valeur Totale").font(.caption); Text((r?.totalValue ?? 0).money).font(.title3.bold()) }
                    VistaCard { Text("Ruptures").font(.caption); Text(L("%@ articles", String(describing: r?.outOfStockCount ?? 0))).font(.title3.bold()) }
                }
                VistaCard {
                    Text("Indicateurs de Stock").font(.headline)
                    Text(L("Articles en stock: %@", String(describing: r?.totalQuantity ?? 0)))
                    Text(L("Alertes niveau faible: %@", String(describing: r?.lowStockCount ?? 0)))
                }
                VistaCard {
                    Text("Distribution par Catégorie").font(.headline)
                    if let cats = r?.categories, !cats.isEmpty {
                        Chart(cats, id: \.category) { c in
                            BarMark(x: .value("Qté", Int(c.total)), y: .value("Catégorie", c.category ?? "?"))
                                .foregroundStyle(by: .value("Catégorie", c.category ?? "?"))
                        }
                        .frame(height: CGFloat(max(cats.count, 2)) * 44)
                    }
                }
                if let url = csvURL {
                    ShareLink(item: url) { Label("🎯 EXPORTER EN EXCEL (CSV)", systemImage: "square.and.arrow.up") }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
            .padding()
        }
        .background(Color.vistaBg.ignoresSafeArea())
        .navigationTitle("Analytiques Avancées")
        .task(id: app.dataVersion) {
            r = try? await app.repo.getReportData()
            if let csv = try? await app.repo.buildStockCsv() {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("StockIT_inventaire.csv")
                try? csv.write(to: url, atomically: true, encoding: .utf8)
                csvURL = url
            }
        }
    }
}

struct AIInsightsView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [AIInsight] = []
    var body: some View {
        List(Array(items.enumerated()), id: \.offset) { _, i in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Pill(text: label(i.type), color: color(i.type))
                    Spacer()
                    Text("PRIORITÉ: \(LV(i.priority))").font(.caption2.bold()).foregroundColor(i.priority == "HIGH" ? .vistaCoral : .secondary)
                }
                Text(i.title).font(.headline)
                Text(i.description_).font(.subheadline).foregroundColor(.secondary)
            }
        }
        .navigationTitle("IA Optimisation")
        .refreshable { await load() }
        .task { await load() }
    }
    private func load() async { items = (try? await app.repo.generateAIInsights()) ?? [] }
    private func label(_ t: InsightType) -> String {
        switch t {
        case .recommendation: return L("RECOMMANDATION")
        case .anomaly: return L("ANOMALIE")
        case .risk: return L("RISQUE")
        case .priceSuggestion: return L("PRIX")
        default: return L("PRÉDICTION")
        }
    }
    private func color(_ t: InsightType) -> Color {
        switch t {
        case .recommendation: return .vistaBlue
        case .anomaly: return .vistaAmber
        case .risk: return .vistaCoral
        case .priceSuggestion: return .vistaMint
        default: return .vistaNavyMuted
        }
    }
}

struct LeaderboardView: View {
    @EnvironmentObject var app: AppState
    @State private var users: [User] = []
    var body: some View {
        List(Array(users.enumerated()), id: \.offset) { i, u in
            HStack {
                Text(i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : "\(i + 1).").frame(width: 32)
                VStack(alignment: .leading) {
                    Text(u.username).font(.headline)
                    Text(L("Niveau %@", String(describing: u.level)) + (u.badges.isEmpty ? "" : " · " + u.badges.split(separator: ",").map { LV(String($0)) }.joined(separator: ", "))).font(.caption).foregroundColor(.secondary)
                }
                Spacer()
                Text(L("%@ pts", String(describing: u.points))).font(.headline).foregroundColor(.vistaBlue)
            }
        }
        .navigationTitle("Classement")
        .task { users = (try? await app.repo.getLeaderboard()) ?? [] }
    }
}

struct AuditView: View {
    @EnvironmentObject var app: AppState
    @State private var logs: [AuditLog] = []
    var body: some View {
        List(logs) { l in
            VStack(alignment: .leading, spacing: 3) {
                HStack { Text(l.action).font(.caption.bold()).foregroundColor(.vistaBlue); Spacer(); Text(formatTimestamp(l.timestamp)).font(.caption2) }
                Text(l.details ?? "").font(.subheadline)
                Text("\(l.userId ?? "-") · \(l.device ?? "")").font(.caption2).foregroundColor(.secondary)
            }
        }
        .navigationTitle("Audit Actions")
        .task { logs = (try? await app.repo.getAuditLogs()) ?? [] }
    }
}

struct UsersView: View {
    @EnvironmentObject var app: AppState
    @State private var users: [User] = []
    var body: some View {
        List {
            ForEach(users) { u in
                HStack {
                    Text(u.username).font(.headline)
                    Spacer()
                    Pill(text: u.role, color: u.isAdmin ? .vistaCoral : .vistaBlue)
                }
            }
            .onDelete { idx in
                let del = idx.map { users[$0] }
                Task { for u in del { _ = try? await app.repo.deleteUser(user: u) }; users = (try? await app.repo.getUsers()) ?? [] }
            }
            .deleteDisabled(!app.isAdmin)
        }
        .navigationTitle("Utilisateurs")
        .task { users = (try? await app.repo.getUsers()) ?? [] }
    }
}

// MARK: - Rapport PDF

struct MonthlyReportView: View {
    @EnvironmentObject var app: AppState
    @State private var loading = false
    @State private var summary: String?
    @State private var pdf: URL?

    var body: some View {
        VStack(spacing: 20) {
            if loading {
                ProgressView("Rédaction du rapport par Claude…")
            } else if let s = summary {
                ScrollView { Text(s).padding() }
                if let pdf {
                    ShareLink(item: pdf) { Label("Partager le rapport", systemImage: "square.and.arrow.up") }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
            Button(summary == nil ? L("Générer le rapport mensuel") : L("Régénérer")) { Task { await generate() } }
                .buttonStyle(PrimaryButtonStyle(color: .vistaNavy))
                .disabled(loading)
        }
        .padding()
        .navigationTitle("Rapport IA")
    }

    private func generate() async {
        loading = true; defer { loading = false }
        let s = (try? await app.repo.generateAIReport()) ?? Strings.shared.AI_ERROR
        summary = s
        if s == Strings.shared.AI_ERROR { pdf = nil; return }
        pdf = PdfReportService.monthlyReport(summary: s)
        if pdf != nil { app.show(L("PDF généré avec succès"), style: .success) }
    }
}

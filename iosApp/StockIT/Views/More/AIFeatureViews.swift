import SwiftUI
import Shared

// MARK: - Qualité de l'IA

/// Tableau de bord : précision réelle de Claude (tickets + reconnaissance), mesurée sur les décisions des techniciens.
struct AIQualityView: View {
    @EnvironmentObject var app: AppState
    @State private var q: AIQuality?

    var body: some View {
        List {
            if let q {
                Section {
                    VStack(spacing: 6) {
                        Text(q.globalScore >= 0 ? "\(q.globalScore) %" : "—")
                            .font(.system(size: 54, weight: .heavy)).foregroundColor(color(Int(q.globalScore)))
                            .accessibilityIdentifier("aiGlobalScore")
                        Text("Score global de l'IA").font(.headline)
                        Text("Pondéré : 40 % acceptation des tickets, 30 % précision des quantités, 30 % reconnaissance.")
                            .font(.caption).foregroundColor(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                Section("🎫 Répartition sur les tickets Jira") {
                    metric("Suggestions appliquées", pct(q.ticketAcceptanceRate), L("%@ / %@ suggestions de Claude", String(describing: q.ticketAccepted), String(describing: q.ticketSuggestions)))
                    metric("Précision des quantités", pct(q.quantityPrecision), L("livré ÷ suggéré, par ticket"))
                    metric("Affectations manuelles", "\(q.manualAssignments)", L("choix faits sans l'IA"))
                }
                Section("📷 Reconnaissance d'équipement") {
                    metric("Nom conservé tel quel", pct(q.visionAccuracy), L("%@ / %@ scans confirmés", String(describing: q.visionKept), String(describing: q.visionScans)))
                    metric("Confiance moyenne annoncée", pct(q.avgConfidence), L("auto-évaluation de Claude"))
                    metric("Scans à faible confiance", "\(q.lowConfidenceScans)",
                           q.lowConfidenceScans > 0 ? L("%@ étaient corrects malgré tout", String(describing: q.lowConfidenceKept)) : L("aucun"))
                    metric("Numéros de série lus", "\(q.serialsRead)", L("envoyés à Jira Assets"))
                }
                Section {
                    Text("Calculé uniquement sur les données de ce téléphone : chaque scan confirmé et chaque sortie sur ticket alimentent ces indicateurs. « — » = pas encore assez de données.")
                        .font(.caption).foregroundColor(.secondary)
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Qualité de l'IA")
        .refreshable { await load() }
        .task(id: app.dataVersion) { await load() }
    }

    private func load() async { q = try? await app.repo.getAIQuality() }
    private func pct(_ v: Int32) -> String { v >= 0 ? "\(v) %" : "—" }
    private func color(_ v: Int) -> Color { v < 0 ? .secondary : v >= 85 ? .vistaMint : v >= 60 ? .vistaAmber : .vistaCoral }
    private func metric(_ title: LocalizedStringKey, _ value: String, _ sub: String) -> some View {
        HStack {
            VStack(alignment: .leading) {
                Text(title)
                Text(sub).font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Text(value).font(.title3.bold()).foregroundColor(.vistaBlue)
        }
    }
}

// MARK: - Prévision des besoins + brouillon de commande

struct ForecastView: View {
    @EnvironmentObject var app: AppState
    @State private var items: [Forecast] = []
    @State private var loading = true
    @State private var showAll = false
    @State private var draftFor: Forecast?

    private var visible: [Forecast] { showAll ? items : items.filter { $0.risk != "OK" || $0.pendingDemand > 0 } }

    var body: some View {
        List {
            Section {
                Text(StockItConfig.shared.isAIConfigured
                     ? L("🧠 Calcul sur les sorties réelles (60 j) + demandes Jira ouvertes, puis analyse par Claude (horizon 30 j).")
                     : L("Calcul local sur les sorties réelles (60 j) + demandes Jira ouvertes. Configurez l'IA pour l'analyse de Claude."))
                    .font(.caption).foregroundColor(.secondary)
                Toggle("Afficher aussi les produits sans risque", isOn: $showAll)
            }
            if loading {
                HStack { ProgressView(); Text("Analyse en cours…").foregroundColor(.secondary) }
            } else if visible.isEmpty {
                Text("✅ Aucune rupture prévue sur 30 jours.").foregroundColor(.secondary)
            }
            ForEach(visible, id: \.productId) { f in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Pill(text: riskLabel(f.risk), color: riskColor(f.risk))
                        Spacer()
                        Text(verbatim: f.daysLeft < 0 ? L("pas de rupture") : f.daysLeft == 0 ? L("rupture maintenant") : L("rupture J+%@", String(describing: f.daysLeft)))
                            .font(.caption.bold()).foregroundColor(f.risk == "CRITICAL" ? .vistaCoral : .secondary)
                    }
                    Text(f.productName).font(.headline)
                    Text(f.rationale).font(.caption).foregroundColor(.secondary)
                    if f.suggestedOrder > 0 {
                        HStack {
                            Text("À commander : \(f.suggestedOrder)").font(.subheadline.bold())
                            Spacer()
                            if app.canEditStock {
                                Button("🛒 Brouillon de commande") { draftFor = f }
                                    .buttonStyle(.borderedProminent).tint(.vistaBlue).font(.caption)
                                    .accessibilityIdentifier("draft_\(f.productId)")
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .navigationTitle("Prévision des besoins")
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: Binding(get: { draftFor.map { ForecastBox(f: $0) } }, set: { draftFor = $0?.f })) { box in
            OrderDraftView(forecast: box.f) { draftFor = nil }
        }
    }

    private func load() async {
        loading = true
        items = (try? await app.repo.getDemandForecast(useAI: true)) ?? []
        loading = false
    }
    private func riskLabel(_ r: String) -> String { r == "CRITICAL" ? L("CRITIQUE") : r == "WARNING" ? L("À SURVEILLER") : "OK" }
    private func riskColor(_ r: String) -> Color { r == "CRITICAL" ? .vistaCoral : r == "WARNING" ? .vistaAmber : .vistaMint }
}

private struct ForecastBox: Identifiable { let f: Forecast; var id: Int64 { f.productId } }

/// Brouillon de demande d'achat préparé par Claude ; le manager ajuste et valide d'un clic.
struct OrderDraftView: View {
    @EnvironmentObject var app: AppState
    let forecast: Forecast
    let onClose: () -> Void
    @State private var draft: OrderDraft?
    @State private var qty = ""
    @State private var busy = false
    @State private var result: InfoAlert?

    var body: some View {
        NavigationStack {
            Form {
                if let d = draft {
                    Section("Produit") {
                        LabeledContent("Article", value: d.productName)
                        LabeledContent("Stock actuel", value: "\(forecast.stock)")
                        TextField("Quantité", text: $qty).keyboardType(.numberPad).accessibilityIdentifier("draftQty")
                    }
                    Section("Fournisseur") {
                        LabeledContent("Fournisseur habituel", value: d.supplier ?? L("à définir"))
                        LabeledContent("Délai", value: L("%@ j", String(describing: d.leadTimeDays)))
                        LabeledContent("Prix unitaire", value: d.unitPrice.money)
                        LabeledContent("Total estimé", value: (d.unitPrice * Double(Int(qty) ?? 0)).money)
                    }
                    Section(d.byAI ? "🧠 Justification rédigée par Claude" : "Justification") {
                        Text(d.justification).font(.subheadline)
                    }
                    Section {
                        Text("La validation crée le bon de commande « En attente » dans StockIT (menu Commandes) et prévient l'équipe sur Slack.")
                            .font(.caption).foregroundColor(.secondary)
                    }
                } else {
                    HStack { ProgressView(); Text("Claude prépare la demande…").foregroundColor(.secondary) }
                }
            }
            .navigationTitle("Brouillon de commande")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler", action: onClose) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("✅ Valider") { Task { await approve() } }
                        .disabled(draft == nil || busy || (Int32(qty) ?? 0) <= 0)
                        .accessibilityIdentifier("approveDraft")
                }
            }
            .task {
                draft = try? await app.repo.draftPurchaseOrder(f: forecast)
                if let d = draft { qty = "\(d.quantity)" }
            }
            .alert(item: $result) { r in
                Alert(title: Text(r.title), message: Text(r.message), dismissButton: .default(Text("OK")) {
                    if r.title.hasPrefix("✅") { onClose() }
                })
            }
        }
    }

    private func approve() async {
        guard let d = draft, let q = Int32(qty), q > 0 else { return }
        busy = true; defer { busy = false }
        let final_ = d.doCopy(productId: d.productId, productName: d.productName, quantity: q, supplier: d.supplier,
                              leadTimeDays: d.leadTimeDays, unitPrice: d.unitPrice,
                              estimatedTotal: d.unitPrice * Double(q), justification: d.justification, byAI: d.byAI)
        let r = try? await app.repo.approveOrderDraft(d: final_)
        if let r, r.success {
            app.dataChanged()
            result = InfoAlert(title: L("✅ Commande créée"),
                               message: L("%@ × %@\nRéférence : %@\nBon de commande ajouté dans « Commandes » (En attente).", String(describing: d.productName), String(describing: q), String(describing: r.detail ?? "")))
        } else {
            result = InfoAlert(title: L("⛔ Refusé"), message: r?.detail ?? L("Erreur inconnue"))
        }
    }
}

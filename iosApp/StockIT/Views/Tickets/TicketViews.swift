import SwiftUI
import Shared

/// ScanOutActivity : sortie de stock répartie sur des tickets Jira (IA, liste, saisie manuelle, sans ticket).
struct ScanOutView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraService()

    enum Step: Equatable { case capture, quantity, method, finished }

    @State private var step: Step = .capture
    @State private var status = L("📤 Cadre l'équipement à sortir puis capture.")
    @State private var busy = false
    @State private var detectedName = ""
    @State private var detectConfidence = -1
    @State private var product: Product?
    @State private var qtyText = ""
    @State private var remaining: Int32 = 0
    @State private var journal: [(String, Int32, String)] = []
    /// Ticket → demandeur (reporter Jira), pour renseigner « Assigned User » dans Assets.
    @State private var reporters: [String: String] = [:]
    /// Résultat de la synchro Jira Assets par ticket (affiché dans le récap).
    @State private var assetNotes: [String] = []

    @State private var alert: InfoAlert?
    @State private var suggestion: [Assignment] = []
    @State private var showSuggestion = false
    @State private var showReview = false
    @State private var showTicketPicker = false
    @State private var manualKey = ""
    @State private var askManual = false
    @State private var perTicket: JiraTicket?
    @State private var perTicketQty = ""
    @State private var detectError: String?
    @State private var showConfirm = false

    var body: some View {
        Group {
            if step == .capture {
                CameraScreen(camera: camera, status: status, busy: busy, captureTitle: L("📷 Capturer"), cancelTitle: L("Annuler"),
                             onCapture: { Task { await capture() } }, onCancel: { dismiss() },
                             onPickPhoto: { data in Task { await analyze(data) } }) { EmptyView() }
                    .sheet(isPresented: $showConfirm) {
                        ConfirmAssetView(detected: detectedName.isEmpty ? nil : detectedName, error: detectError,
                                         requireExisting: true, confidence: detectConfidence) { _, p in
                            showConfirm = false
                            guard let p else { return }
                            let d = detectedName.isEmpty ? nil : detectedName, c = Int32(detectConfidence)
                            Task { try? await app.repo.recordVisionFeedback(detected: d, finalName: p.name, confidence: c, serialRead: false) }
                            product = p
                            camera.stop()
                            step = .quantity
                        } onRetake: {
                            showConfirm = false
                        }
                    }
            } else {
                NavigationStack { flow }
            }
        }
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .alert(item: $alert) { a in Alert(title: Text(a.title), message: Text(a.message), dismissButton: .default(Text("OK"))) }
    }

    @ViewBuilder private var flow: some View {
        Form {
            Section {
                LabeledContent("Équipement", value: product?.name ?? detectedName)
                LabeledContent("Stock disponible", value: "\(product?.quantity ?? 0)")
                if step != .quantity { LabeledContent("Reste à sortir", value: "\(remaining)") }
            }
            if busy { Section { HStack { ProgressView(); Text(status) } } }

            switch step {
            case .quantity:
                Section("Combien de \"\(product?.name ?? "")\" sortir ?") {
                    TextField("ex: 5", text: $qtyText).keyboardType(.numberPad)
                    Button("Continuer") { startAssign() }
                }
            case .method:
                Section("Reste \(remaining) × \(product?.name ?? "")") {
                    Button("🧠 Laisser Claude décider (auto)") { Task { await aiMode() } }
                    Button("🔎 Choisir dans la liste des tickets") { showTicketPicker = true }
                    Button("✏️ Taper un ID manuellement") { manualKey = ""; askManual = true }
                    Button("⏭️ Skip — sortir sans ticket") { Task { await commit("NO-TICKET", remaining, "Sortie libre") } }
                }
                .disabled(busy)
            case .finished:
                Section("🟢 Sortie enregistrée") { Text(recap).font(.subheadline) }
                Section { Button("Terminer") { dismiss() } }
            case .capture:
                EmptyView()
            }
            if !journal.isEmpty && step != .finished {
                Section("Déjà assigné") { ForEach(Array(journal.enumerated()), id: \.offset) { _, j in Text("• \(j.0) → \(j.1)") } }
            }
        }
        .navigationTitle("Sortie équipement")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { if step != .finished { Button(L("Annuler")) { dismiss() } } }
        .alert("🧠 Suggestion IA", isPresented: $showSuggestion) {
            Button("Appliquer") { Task { await applySuggestion() } }
            Button("Non, autre choix", role: .cancel) {}
        } message: { Text(suggestionText) }
        .sheet(isPresented: $showReview) {
            SuggestionReviewSheet(assignments: suggestion, remaining: remaining) { accepted in
                showReview = false
                suggestion = accepted
                Task { await applySuggestion() }
            } onCancel: { showReview = false }
        }
        .alert("Saisis l'ID du ticket", isPresented: $askManual) {
            TextField("ex: ETXTUN-42", text: $manualKey).textInputAutocapitalization(.characters)
            Button("Vérifier") { Task { await verifyManual() } }
            Button(L("Annuler"), role: .cancel) {}
        }
        .alert("Assigner à \(perTicket?.key ?? "")", isPresented: Binding(get: { perTicket != nil }, set: { if !$0 { perTicket = nil } })) {
            TextField("Quantité", text: $perTicketQty).keyboardType(.numberPad)
            Button("Confirmer") {
                let t = perTicket
                perTicket = nil
                guard let t, let q = Int32(perTicketQty), q >= 1, q <= remaining else { app.show(L("Quantité invalide"), style: .urgent); return }
                Task { await commit(t.key, q, "Choix manuel") }
            }
            Button(L("Annuler"), role: .cancel) { perTicket = nil }
        } message: {
            Text((perTicket?.summary.map { $0 + "\n\n" } ?? "") + L("Combien de \"%@\" pour ce ticket ? (max %@)", String(describing: product?.name ?? ""), String(describing: remaining)))
        }
        .sheet(isPresented: $showTicketPicker) {
            NavigationStack {
                TicketPickerView { t in
                    showTicketPicker = false
                    perTicketQty = "\(remaining)"
                    if let rep = t.reporter, !rep.isEmpty { reporters[t.key] = rep }
                    perTicket = t
                }
            }
        }
    }

    private var suggestionText: String {
        var s = L("Claude propose :\n\n")
        var total: Int32 = 0
        for a in suggestion {
            let c = Int(a.confidence)
            s += "• \(a.ticketId) → \(a.qty)" + (c >= 0 ? L("  (confiance %@ %%)", String(describing: c)) : "") + "\n    \(LV(a.reason))\n\n"
            total += a.qty
        }
        s += L("Total assigné : %@ / %@\n", String(describing: total), String(describing: remaining))
        if total < remaining { s += L("Restant : %@ (tu choisiras la méthode ensuite)", String(describing: remaining - total)) }
        return s
    }

    private var recap: String {
        var s = L("Équipement : %@\n\n", String(describing: product?.name ?? ""))
        var total: Int32 = 0
        for j in journal { s += "• \(j.0) → \(j.1)\n    \(j.2)\n"; total += j.1 }
        let notes = assetNotes.filter { !$0.isEmpty }
        if !notes.isEmpty { s += L("\nJira Assets (OUT) :\n") + notes.map { "• \($0)" }.joined(separator: "\n") + "\n" }
        return s + L("\nTotal sorti : %@", String(describing: total))
    }

    // MARK: étapes

    private func capture() async {
        guard let jpeg = try? await camera.capturePhoto() else { status = L("❌ Capture échouée"); return }
        await analyze(jpeg)
    }

    /// Claude identifie l'équipement, puis l'utilisateur confirme le produit du stock
    /// (correspondance tolérante : accents, synonymes, marque/modèle).
    private func analyze(_ jpeg: Data) async {
        busy = true; defer { busy = false }
        status = L("🧠 Analyse par Claude…")
        let r = await VisionService.identify(jpeg: jpeg)
        detectedName = r.name ?? ""
        detectConfidence = r.confidence
        detectError = r.name == nil ? (r.error ?? L("réponse vide")) : nil
        status = r.name.map { L("🧠 Détecté : %@", String(describing: $0)) } ?? L("❌ Non reconnu — choisis le produit")
        showConfirm = true
    }

    private func startAssign() {
        guard let p = product, let q = Int32(qtyText), q > 0 else { app.show(L("Quantité invalide"), style: .urgent); return }
        guard q <= p.quantity else { app.show(L("Stock insuffisant (dispo: %@)", String(describing: p.quantity)), style: .urgent); return }
        remaining = q
        journal = []
        assetNotes = []
        step = .method
    }

    private func aiMode() async {
        guard let p = product else { return }
        busy = true; status = L("🧠 Chargement tickets Jira + analyse Claude…")
        defer { busy = false }
        guard let res = try? await JiraClient.shared.loadOpenTickets(maxResults: 50), let tickets = res.tickets else {
            alert = InfoAlert(title: L("❌ Jira"), message: L("Impossible de charger les tickets : erreur réseau")); return
        }
        let done = Set((try? await app.repo.getFullyFulfilledTicketIds()) ?? [])
        let filtered = tickets.filter { !done.contains($0.key) }
        guard !filtered.isEmpty else {
            alert = InfoAlert(title: L("🧠 Aucun ticket à analyser"),
                              message: L("Tous les tickets Jira ouverts ont déjà été traités (cache local). Utilise un autre mode."))
            return
        }
        let r = try? await TicketMatcher.shared.assign(equipmentName: p.name, quantity: remaining, tickets: filtered)
        // Garde-fou : on ne garde que les tickets réellement envoyés à Claude (pas d'ID inventé).
        let known = Set(filtered.map { $0.key })
        for t in filtered { if let rep = t.reporter, !rep.isEmpty { reporters[t.key] = rep } }
        guard let list = r?.assignments?.filter({ known.contains($0.ticketId) }), !list.isEmpty else {
            alert = InfoAlert(title: L("🧠 Claude n'a rien proposé"), message: r?.error ?? L("Aucun ticket ne correspond à cet équipement."))
            return
        }
        for a in list {
            let summary = filtered.first { $0.key == a.ticketId }?.summary
            try? await app.repo.saveLlmAnalysis(ticketId: a.ticketId, equipmentName: p.name, ticketSummary: summary,
                                                suggestedQty: a.qty, reason: a.reason)
        }
        suggestion = list
        // Confiance faible sur au moins un ticket → revue ticket par ticket (pas d'application en un clic).
        if list.contains(where: { Confidence.shared.requiresManualCheck(c: $0.confidence) }) {
            showReview = true
        } else {
            showSuggestion = true
        }
    }

    private func applySuggestion() async {
        for a in suggestion where remaining > 0 {
            if !(await commit(a.ticketId, min(a.qty, remaining), a.reason, finishIfDone: false)) { return }
        }
        if remaining <= 0 { await finish() }
    }

    private func verifyManual() async {
        let key = manualKey.trimmingCharacters(in: .whitespaces).uppercased()
        guard !key.isEmpty else { return }
        busy = true; status = L("⏳ Vérification %@…", String(describing: key))
        defer { busy = false }
        let r = try? await JiraClient.shared.getIssue(key: key)
        guard let t = r?.ticket else {
            alert = InfoAlert(title: L("❌ Ticket introuvable"), message: L("Impossible de trouver %@ (%@)", String(describing: key), String(describing: r?.error ?? "not_found")))
            return
        }
        perTicketQty = "\(remaining)"
        if let rep = t.reporter, !rep.isEmpty { reporters[t.key] = rep }
        perTicket = t
    }

    @discardableResult
    private func commit(_ ticket: String, _ q: Int32, _ reason: String, finishIfDone: Bool = true) async -> Bool {
        guard let p = product else { return false }
        let ok = (try? await app.repo.recordExitToTicket(product: p, qty: q, ticketId: ticket, assignmentReason: reason))?.boolValue ?? false
        guard ok else { app.show(L("Stock insuffisant pour %@", String(describing: ticket)), style: .urgent); return false }
        remaining -= q
        journal.append((ticket, q, reason))
        try? await app.repo.recordDeliveryToTicket(ticketId: ticket, equipmentName: p.name, qty: q, reasonIfManual: reason)
        // Jira Assets : objets « In stock » → « OUT / Checked Out » + commentaire sur le ticket.
        status = L("⏳ Mise à jour Jira Assets…")
        if let a = try? await app.repo.syncAssetsCheckOut(name: p.name, qty: q, ticketKey: ticket,
                                                         assignedTo: reporters[ticket], aiReason: reason) {
            assetNotes.append(a.success ? "\(ticket) : \(a.detail ?? "OK")"
                              : (a.detail == "assets_not_configured" ? "" : "\(ticket) : ⚠️ \(a.detail ?? "échec")"))
        }
        if let fresh = try? await app.repo.getProduct(id: p.id) { product = fresh }
        app.dataChanged()
        if finishIfDone && remaining <= 0 { await finish() }
        return true
    }

    private func finish() async {
        let total = journal.reduce(Int32(0)) { $0 + $1.1 }
        let name = product?.name ?? ""
        Bg.slack(":package: [StockIT] Sortie de \(total) x \(name) vers \(journal.count) ticket(s)")
        NotificationService.show(L("StockIT — Sortie enregistrée"), "\(total) x \(name)")
        Bg.email("Sortie de stock : \(name)",
                 "📤 Une sortie de stock vient d'être enregistrée.\n\n• Produit         : \(name)\n• Quantité totale : \(total)\n• Tickets servis  : \(journal.count)\n\nDétail par ticket :\n" + recap)
        Haptics.success()
        step = .finished
    }
}

// MARK: - Revue des suggestions IA (confiance faible)

/// Affiché quand au moins une suggestion de Claude a une confiance < 60 % :
/// les tickets fiables sont pré-cochés, les autres doivent être cochés explicitement.
struct SuggestionReviewSheet: View {
    let assignments: [Assignment]
    let remaining: Int32
    let onApply: ([Assignment]) -> Void
    let onCancel: () -> Void
    @State private var checked: Set<String> = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Claude n'est pas sûr de certaines affectations. Coche uniquement celles que tu valides.")
                        .font(.subheadline)
                }
                ForEach(assignments, id: \.ticketId) { a in
                    Button {
                        if checked.contains(a.ticketId) { checked.remove(a.ticketId) } else { checked.insert(a.ticketId) }
                    } label: {
                        HStack(alignment: .top) {
                            Image(systemName: checked.contains(a.ticketId) ? "checkmark.square.fill" : "square")
                                .foregroundColor(.vistaBlue)
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text("\(a.ticketId) → \(a.qty)").font(.headline).foregroundColor(.primary)
                                    Spacer()
                                    ConfidenceBadge(value: Int(a.confidence))
                                }
                                Text(verbatim: LV(a.reason)).font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                    .accessibilityIdentifier("review_\(a.ticketId)")
                }
            }
            .navigationTitle("🧠 Vérifier la suggestion")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Autre choix", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Appliquer (\(checked.count))") {
                        onApply(assignments.filter { checked.contains($0.ticketId) })
                    }
                    .disabled(checked.isEmpty)
                }
            }
            .onAppear {
                checked = Set(assignments.filter { !Confidence.shared.requiresManualCheck(c: $0.confidence) }.map { $0.ticketId })
            }
        }
        .interactiveDismissDisabled()
    }
}

// MARK: - TicketListActivity

/// Liste des tickets Jira ouverts : recherche, filtres, swipe (local), sélection.
struct TicketPickerView: View {
    var onPick: ((JiraTicket) -> Void)? = nil
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var tickets: [JiraTicket] = []
    @State private var loading = true
    @State private var error: String?
    @State private var query = ""
    @State private var filter = "Tous"
    private let filters = ["Tous", "Highest", "En retard", "Assignés à moi"]

    private var visible: [JiraTicket] {
        let now = nowMillis()
        let me = app.user?.username.lowercased() ?? ""
        return tickets.filter { t in
            guard t.matches(query: query) else { return false }
            switch filter {
            case "Highest": return t.priorityWeight() >= 5
            case "En retard": return t.isOverdue(now: now)
            case "Assignés à moi": return !me.isEmpty && (t.assignee?.lowercased().contains(me) ?? false)
            default: return true
            }
        }
    }

    var body: some View {
        List {
            Picker("Filtre", selection: $filter) { ForEach(filters, id: \.self) { Text(LocalizedStringKey($0)) } }
                .pickerStyle(.segmented)
                .onChange(of: filter) { _ in Haptics.tap() }
            if loading {
                ForEach(0..<5, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 8).fill(Color.gray.opacity(0.2)).frame(height: 60).redacted(reason: .placeholder)
                }
            } else if let e = error {
                EmptyStateView(icon: "exclamationmark.triangle", title: L("Impossible de charger"), subtitle: e)
            } else if tickets.isEmpty {
                EmptyStateView(icon: "checkmark.seal", title: "Aucun ticket ouvert", subtitle: "Rien à traiter — 🎉 profitez d'une pause.")
            } else if visible.isEmpty {
                EmptyStateView(icon: "magnifyingglass", title: "Aucun résultat", subtitle: "Essayez un autre filtre ou une autre recherche.")
            }
            ForEach(visible, id: \.key) { t in
                TicketRow(ticket: t)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if let onPick { onPick(t) }
                    }
                    .swipeActions(edge: .leading) {
                        Button { remove(t); app.show(L("Assigné à moi : %@", String(describing: t.key)), style: .success) } label: {
                            Label("Assigner", systemImage: "person.fill.checkmark")
                        }.tint(.vistaMint)
                    }
                    .swipeActions(edge: .trailing) {
                        Button { remove(t); app.show(L("Reporté : %@", String(describing: t.key))) } label: {
                            Label("Reporter", systemImage: "clock.arrow.circlepath")
                        }.tint(.vistaAmber)
                    }
            }
        }
        .navigationTitle("Tickets Jira ouverts")
        .searchable(text: $query, prompt: "Rechercher (key, sujet, description…)")
        .refreshable { await load() }
        .task { await load() }
        .toolbar { if onPick != nil { Button(L("Annuler")) { dismiss() } } }
    }

    private func remove(_ t: JiraTicket) { tickets.removeAll { $0.key == t.key } }

    private func load() async {
        loading = true; defer { loading = false }
        let r = try? await JiraClient.shared.loadOpenTickets(maxResults: 50)
        if let list = r?.tickets { tickets = list; error = nil } else { error = r?.error ?? L("Erreur réseau") }
    }
}

/// Onglet Tickets (consultation).
struct TicketBrowserView: View {
    var body: some View { TicketPickerView(onPick: nil) }
}

struct TicketRow: View {
    let ticket: JiraTicket
    var body: some View {
        let w = ticket.priorityWeight()
        let color: Color = w >= 5 ? .vistaCoral : w == 4 ? .vistaAmber : w == 3 ? .vistaBlue : .vistaNavyFaint
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 4)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(ticket.key).font(.headline)
                    Pill(text: ticket.priority ?? "?", color: color, foreground: w <= 2 ? .vistaNavyMuted : .white)
                    if w >= 4 { Circle().fill(Color.vistaCoral).frame(width: 8, height: 8) }
                    Spacer()
                    if let sla = ticket.slaLabel(now: nowMillis()) {
                        Pill(text: sla, color: sla.hasPrefix("SLA") ? .vistaUrgencySoft : .vistaBg,
                             foreground: sla.hasPrefix("SLA") ? .vistaCoral : .vistaNavy)
                    }
                }
                if let s = ticket.summary { Text(s).font(.subheadline) }
                if let d = ticket.description_, !d.isEmpty { Text(d).font(.caption).foregroundColor(.secondary).lineLimit(2) }
                Text((ticket.status ?? "?") + (ticket.assignee.map { " · @\($0)" } ?? "")).font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

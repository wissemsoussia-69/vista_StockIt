import SwiftUI
import Shared

// MARK: - ScanAssetActivity

/// Scan d'un équipement : photo → Claude → confirmation du nom → kit anti-oubli (écrans)
/// → facture (PO) → étiquette carton → ajout au stock.
/// À chaque étape, « Passer » continue le scénario : l'équipement est toujours enregistré à la fin.
struct ScanAssetView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraService()

    /// Étape affichée par-dessus la caméra (une seule à la fois : enchaînement fiable).
    enum Step: Identifiable {
        case confirm, kit, askInvoice, invoice, askLabel, label
        var id: Int { hashValue }
    }

    @State private var status = L("Pointez la caméra vers un objet informatique puis capturez.")
    @State private var busy = false
    @State private var detected: String?
    @State private var detectError: String?
    @State private var confidence = -1
    @State private var serial: String?
    @State private var matchPulse = false

    @State private var step: Step?
    @State private var name = ""
    @State private var existing: Product?
    @State private var kitPower = false
    @State private var kitHdmi = false
    @State private var reason = "Scan IA StockIT PFE"
    @State private var po: SelectedPO?
    @State private var alert: InfoAlert?
    @State private var done: String?

    var body: some View {
        CameraScreen(camera: camera, status: status, busy: busy,
                     captureTitle: L("Capturer et analyser"), cancelTitle: L("Fermer"),
                     onCapture: { Task { await capture() } }, onCancel: { dismiss() },
                     onPickPhoto: { data in Task { await analyze(data) } }) {
            ZStack {
                if busy {
                    RoundedRectangle(cornerRadius: 24).stroke(Color.vistaBlueSoft, lineWidth: 3)
                        .padding(50).opacity(0.8)
                }
                if let d = detected {
                    VStack {
                        Spacer().frame(height: 120)
                        Label("Match : \(d)", systemImage: "checkmark.seal.fill")
                            .font(.headline).padding(12)
                            .background(Color.vistaMint, in: Capsule()).foregroundColor(.vistaNavy)
                            .scaleEffect(matchPulse ? 1 : 0.6).opacity(matchPulse ? 1 : 0)
                        Spacer()
                    }
                }
            }
        }
        .onAppear { camera.start() }
        .onDisappear { camera.stop() }
        .sheet(item: $step) { s in sheet(for: s) }
        .alert(item: $alert) { Alert(title: Text($0.title), message: Text($0.message)) }
        .alert(L("🟢 Équipement enregistré"), isPresented: Binding(get: { done != nil }, set: { if !$0 { done = nil } })) {
            Button("OK") { dismiss() }
            Button("Scanner un autre") { reset() }
        } message: { Text(done ?? "") }
    }

    // MARK: étapes

    @ViewBuilder private func sheet(for s: Step) -> some View {
        switch s {
        case .confirm:
            ConfirmAssetView(detected: detected, error: detectError, requireExisting: false,
                             confidence: confidence, serial: serial) { n, p in
                name = n; existing = p
                let d = detected, c = Int32(confidence), s = serial != nil
                Task { try? await app.repo.recordVisionFeedback(detected: d, finalName: n, confidence: c, serialRead: s) }
                go(KitRules.shared.requiresKit(name: n) ? .kit : .askInvoice)
            } onRetake: { step = nil; reset() } onSerial: { s in serial = s }
        case .kit:
            kitSheet
        case .askInvoice:
            ChoiceSheet(title: L("📄 Scanner la facture liée ?"),
                        message: L("Équipement : %@\n\nPrends en photo la facture pour rattacher automatiquement le PO. Sinon l'équipement sera ajouté au stock sans PO.", String(describing: name)),
                        primary: L("📷 Scanner la facture"), secondary: L("Enregistrer sans PO"),
                        onPrimary: { go(.invoice) }, onSecondary: { finishSave(label: nil) })
        case .invoice:
            ReceivePackageView(mode: .forEquipment(name), embedded: true) { selected in
                po = selected
                go(.askLabel)
            } onSkip: {
                go(.askLabel)
            }
        case .askLabel:
            ChoiceSheet(title: L("🏷️ Scanner l'étiquette du carton ?"),
                        message: L("Équipement : %@\n", String(describing: name)) + (po.map { L("PO facture : %@\n", String(describing: $0.number)) } ?? "") +
                                 L("\nPrends en photo l'étiquette du carton pour obtenir le nom exact du produit, la quantité, la référence article et la marque."),
                        primary: L("📷 Scanner l'étiquette"), secondary: L("Enregistrer sans étiquette"),
                        onPrimary: { go(.label) }, onSecondary: { finishSave(label: nil) })
        case .label:
            PackageLabelView(expectedPo: po?.number, embedded: true) { label in
                finishSave(label: label)
            } onSkip: {
                finishSave(label: nil)
            }
        }
    }

    /// Ferme l'étape courante puis ouvre la suivante (après l'animation de fermeture).
    private func go(_ next: Step) {
        step = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { step = next }
    }

    private func finishSave(label: PackageLabel?) {
        step = nil
        Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            await save(label: label)
        }
    }

    private func reset() {
        detected = nil; detectError = nil; matchPulse = false; po = nil; existing = nil; name = ""
        confidence = -1; serial = nil
        reason = "Scan IA StockIT PFE"
        status = L("Pointez la caméra vers un objet informatique puis capturez.")
        camera.start()
    }

    private var kitSheet: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Cochez les dépendances effectivement présentes.\n• Kit COMPLET → ajout normal au stock.\n• Kit INCOMPLET → alerte Slack + ticket Jira automatique.")
                        .font(.subheadline)
                }
                Toggle("Câble d'alimentation", isOn: $kitPower)
                Toggle("Câble HDMI", isOn: $kitHdmi)
            }
            .navigationTitle("Kit Anti-Oubli — \(name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Annuler")) { step = nil } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Valider") { Task { await kitResult() } }
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }

    // MARK: analyse

    private func capture() async {
        Haptics.tap()
        busy = true; status = L("Capture…")
        let jpeg: Data
        do { jpeg = try await camera.capturePhoto() } catch {
            busy = false
            status = L("Capture échouée")
            Bg.slack(":warning: [StockIT] Capture caméra échouée : \(error.localizedDescription)")
            Bg.email("Incident caméra (scan équipement)",
                     "⚠️ La capture caméra a échoué pendant un scan équipement.\n\n• Détail technique : \(error.localizedDescription)\n• Écran            : ScanAssetView (iOS)\n\n👉 Vérifier permissions caméra et état du terminal.")
            return
        }
        await analyze(jpeg)
    }

    private func analyze(_ jpeg: Data) async {
        busy = true; detected = nil; detectError = nil; matchPulse = false
        kitPower = false; kitHdmi = false; po = nil; existing = nil; confidence = -1; serial = nil
        status = L("🧠 Analyse par Claude…")
        let r = await VisionService.identify(jpeg: jpeg)
        busy = false
        confidence = r.confidence
        serial = r.serial
        if let n = r.name, !n.isEmpty {
            detected = n
            status = L("🧠 Objet détecté : %@", String(describing: n)) + (r.confidence >= 0 ? " (\(r.confidence) %)" : "")
            Haptics.success()
            withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { matchPulse = true }
        } else {
            let err = r.error ?? L("réponse vide")
            detectError = err
            status = L("❌ Analyse impossible (%@)", String(describing: err))
            Haptics.error()
            Bg.slack(":x: [StockIT] Anomalie scan IA : \(err)")
            Bg.email("Anomalie détection IA Claude",
                     "🤖 L'IA n'a pas pu identifier l'objet scanné.\n\n• Erreur renvoyée : \(err)\n\nCas possibles : image floue, cadrage incorrect, limite du modèle.")
        }
        // Dans tous les cas : confirmation (nom modifiable / saisie manuelle).
        step = .confirm
    }

    private func kitResult() async {
        if kitPower && kitHdmi {
            reason = "Kit complet (Alim + HDMI)"
            go(.askInvoice)
            return
        }
        step = nil
        status = L("⛔ Kit incomplet — validation refusée.")
        Haptics.error()
        Bg.slack(":rotating_light: [StockIT] Kit anti-oubli incomplet pour un Écran (Alim=\(kitPower), HDMI=\(kitHdmi))")
        Bg.email("Kit anti-oubli incomplet (écran)",
                 "⛔ Un écran a été scanné sans tous les accessoires obligatoires.\n\n• Équipement : \(name)\n• Alimentation présente : \(kitPower)\n• Câble HDMI présent    : \(kitHdmi)\n\nUn ticket Jira a été créé automatiquement pour tracer l'incident.")
        let key = StockItConfig.shared.jiraProjectKey
        let r = try? await JiraClient.shared.createTask(
            projectKey: key, summary: "[StockIT] Kit accessoires manquant pour un ecran",
            details: "Un technicien a scanne un ecran (\(name)) sans confirmer toutes les dependances (Alim=\(kitPower), HDMI=\(kitHdmi)).")
        try? await Task.sleep(nanoseconds: 450_000_000)
        let testNote = StockItConfig.shared.testMode ? L("\n\n(Mode test : aucun ticket réel créé)") : ""
        if let r, r.success, let k = r.detail {
            status = L("⛔ Kit incomplet — Ticket Jira créé : %@", String(describing: k))
            alert = InfoAlert(title: L("🟢 Ticket Jira créé"),
                              message: L("Clé : %@\n\nProjet : %@\nURL : %@", String(describing: k), String(describing: key), String(describing: JiraClient.shared.browseUrl(key: k))) + testNote)
        } else {
            let res = r?.detail ?? L("erreur réseau")
            status = L("⛔ Jira KO — %@", String(describing: res))
            Bg.slack(":warning: [StockIT] Echec creation Jira : \(res)")
            alert = InfoAlert(title: L("🔴 Jira KO — projet %@", String(describing: key)), message: L("Détail brut renvoyé par Jira :\n\n%@", String(describing: res)))
        }
    }

    // MARK: enregistrement

    private func save(label: PackageLabel?) async {
        guard !name.isEmpty else { return }
        var finalName = name, desc: String? = nil, tag = "ASSET-\(nowMillis())", qty: Int32 = 1
        if let l = label {
            if let n = l.productName, !n.isEmpty { finalName = n }
            qty = l.quantity
            desc = (l.brand.map { "\($0) — " } ?? "") + (l.articleNumber.map { "Art. \($0)" } ?? "Scan IA")
            if let a = l.articleNumber { tag = a }
        }
        let suffix = po.map { L(" (lié à %@)", String(describing: $0.number)) } ?? ""

        if let p = existing, label == nil || label?.productName == nil || label?.productName == p.name {
            // Produit déjà en stock : on augmente sa quantité.
            // syncAssets: false → les unités (avec n° de série) sont créées juste après par syncAssetsCheckIn.
            guard (try? await app.repo.updateQuantity(product: p, delta: qty, syncAssets: false)) != nil else {
                alert = InfoAlert(title: L("⛔ Erreur"), message: L("Mise à jour du stock impossible.")); return
            }
            finalName = p.name
        } else {
            let id = try? await app.repo.addProduct(
                name: finalName, category: ProductMatcher.shared.categoryFor(detected: finalName), categoryId: nil,
                desc: desc, assetTag: tag, quantity: qty,
                unitPrice: 0, mfgDate: "", expDate: "", reason: reason,
                poNumber: po?.number, poDescription: po?.description, receivedFrom: po?.supplier,
                articleNumber: label?.articleNumber, brand: label?.brand, packagePoNumber: label?.poOnLabel,
                syncAssets: false)
            guard id != nil else {
                alert = InfoAlert(title: L("⛔ Permission refusée"), message: L("Seuls les rôles ADMIN / MANAGER peuvent ajouter au stock."))
                return
            }
        }
        app.dataChanged()
        // Numéros de série : ceux de l'étiquette (un par unité), sinon celui lu sur la photo.
        var serials: [String] = label?.serials ?? []
        if let s = serial, !s.isEmpty, !serials.contains(s) { serials.insert(s, at: 0) }
        // Jira Assets (CMDB) : un objet « In stock » par unité reçue.
        let assets = try? await app.repo.syncAssetsCheckIn(
            name: finalName, qty: qty, brand: label?.brand, model: label?.articleNumber,
            poNumber: po?.number ?? label?.poOnLabel, vendor: po?.supplier, serials: serials)
        // Jira Assets = source de vérité : on réaligne l'inventaire après création des objets.
        if assets?.success == true { Task { await app.syncAssets(silent: true) } }
        app.show(L("%@ x%@ ajouté au stock%@", String(describing: finalName), String(describing: qty), String(describing: suffix)), style: .success)
        NotificationService.show(L("StockIT — Réception"), L("%@ x%@ enregistré%@", String(describing: finalName), String(describing: qty), String(describing: suffix)))
        if let p = po {
            Bg.slack(":white_check_mark: [StockIT] \(finalName) x\(qty) ajouté au stock et rattaché à \(p.number)" + (p.supplier.map { " (\($0))" } ?? ""))
        }
        Bg.email("Nouvel équipement enregistré : \(finalName) x\(qty)",
                 "✅ Un nouvel équipement vient d'être ajouté au stock via scan IA.\n\n• Nom         : \(finalName)\n• Quantité    : \(qty)\n• PO lié      : \(po?.number ?? "aucun")\n• Fournisseur : \(po?.supplier ?? "non renseigné")")
        var lines = [L("• Nom : %@", String(describing: finalName)), L("• Quantité : %@", String(describing: qty)) + (existing != nil ? L(" (ajouté au produit existant)") : "")]
        if let b = label?.brand { lines.append(L("• Marque : %@", String(describing: b))) }
        if let a = label?.articleNumber { lines.append(L("• Art.-No. : %@", String(describing: a))) }
        lines.append(L("• Motif : %@", String(describing: reason)))
        if let p = po {
            lines.append(L("• PO facture : %@", String(describing: p.number)))
            if !p.description.isEmpty { lines.append(L("• Description PO : %@", String(describing: p.description))) }
        }
        if let pl = label?.poOnLabel { lines.append(L("• PO étiquette : %@", String(describing: pl))) }
        if let s = po?.supplier { lines.append(L("• Fournisseur : %@", String(describing: s))) }
        if !serials.isEmpty { lines.append(L("• N° de série : ") + serials.prefix(5).joined(separator: ", ") + (serials.count > 5 ? "…" : "")) }
        if confidence >= 0 { lines.append(L("• Confiance IA : %@ %%", String(describing: confidence))) }
        if let a = assets {
            lines.append(a.success ? L("• Jira Assets : %@", String(describing: a.detail ?? "OK")) : L("• Jira Assets : ⚠️ %@", String(describing: a.detail ?? "échec")))
        }
        if StockItConfig.shared.testMode { lines.append(L("\n(Mode test : pas de Slack / e-mail / Jira réels)")) }
        done = lines.joined(separator: "\n")
    }
}

/// Feuille de choix à deux boutons (remplace confirmationDialog, plus fiable dans les enchaînements).
struct ChoiceSheet: View {
    let title: String
    let message: String
    let primary: String
    let secondary: String
    let onPrimary: () -> Void
    let onSecondary: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(title).font(.title3.bold()).multilineTextAlignment(.center)
            Text(message).font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
            Button(primary, action: onPrimary).buttonStyle(PrimaryButtonStyle())
            Button(secondary, action: onSecondary).buttonStyle(PrimaryButtonStyle(color: .vistaNavyMuted))
        }
        .padding(24)
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }
}

struct SelectedPO {
    let number: String
    let description: String
    let supplier: String?
}

// MARK: - ReceivePackageActivity + POSelectionActivity

struct ReceivePackageView: View {
    enum Mode { case standalone, forEquipment(String) }
    let mode: Mode
    /// true : affiché comme étape du scan d'équipement (pas de dismiss propre, on rend la main).
    var embedded: Bool = false
    var onSelected: ((SelectedPO) -> Void)? = nil
    /// Appelé quand l'utilisateur passe la facture (le scénario continue).
    var onSkip: (() -> Void)? = nil

    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraService()
    @State private var status = ""
    @State private var busy = false
    @State private var note: ParsedNote?
    @State private var showSelection = false
    @State private var resultText: String?
    @State private var matchedPO: PurchaseOrder?
    @State private var alert: InfoAlert?
    @State private var success: String?

    private var equipment: String? { if case .forEquipment(let n) = mode { return n }; return nil }

    var body: some View {
        CameraScreen(camera: camera, status: status, busy: busy, captureTitle: L("📷 Capturer"),
                     cancelTitle: embedded ? L("Passer") : L("Annuler"),
                     onCapture: { Task { await capture() } }, onCancel: { skip() },
                     onPickPhoto: { data in Task { await process(data) } }) { EmptyView() }
            .onAppear {
                camera.quality = .quality
                status = equipment.map { L("📄 Facture pour \"%@\" — cadre puis capture.", String(describing: $0)) } ?? L("📄 Cadre le bon de livraison puis capture.")
                camera.start()
            }
            .onDisappear { camera.stop() }
            .sheet(isPresented: $showSelection) {
                if let n = note {
                    NavigationStack {
                        POSelectionView(equipmentName: equipment, note: n) { sel in
                            showSelection = false
                            camera.stop()
                            if embedded { onSelected?(sel) } else { onSelected?(sel); dismiss() }
                        } onSkip: {
                            showSelection = false
                            skip()
                        }
                    }
                }
            }
            .alert(item: $alert) { a in
                Alert(title: Text(a.title), message: Text(a.message),
                      primaryButton: .default(Text("Réessayer")),
                      secondaryButton: .cancel(Text(embedded ? L("Continuer sans PO") : L("Fermer"))) { if embedded { skip() } })
            }
            .alert(L("📄 Résultat facture"), isPresented: Binding(get: { resultText != nil }, set: { if !$0 { resultText = nil } })) {
                if let po = matchedPO, po.status != "Reçu" {
                    Button("✅ Valider PO#\(po.id)") { Task { await validate(po) } }
                }
                Button(L("Fermer"), role: .cancel) {}
            } message: { Text(resultText ?? "") }
            .alert(L("🟢 Réception enregistrée"), isPresented: Binding(get: { success != nil }, set: { if !$0 { success = nil } })) {
                Button("OK") { dismiss() }
            } message: { Text(success ?? "") }
    }

    private func skip() {
        camera.stop()
        if embedded { onSkip?() } else { dismiss() }
    }

    private func capture() async {
        guard let data = try? await camera.capturePhoto() else { status = L("❌ Capture échouée"); return }
        await process(data)
    }

    private func process(_ data: Data) async {
        busy = true; defer { busy = false }
        guard let img = UIImage(data: data)?.normalized() else {
            status = L("❌ Décodage image échoué"); return
        }
        status = L("🧠 [1/3] Lecture du texte (Apple Vision)…")
        let ocr = await VisionService.recognizeText(img)
        status = L("⏳ [2/3] Analyse par Claude (%@ caractères)…", String(describing: ocr?.count ?? 0))
        guard let n = try? await DeliveryNoteParser.shared.parseOcr(ocrText: ocr) else {
            alert = InfoAlert(title: L("❌ Réception colis"), message: L("Extraction IA impossible.")); return
        }
        if let err = n.error {
            alert = InfoAlert(title: L("❌ Réception colis"),
                              message: L("Extraction IA impossible.\nErreur : %@", String(describing: err)) + (n.rawOcr.map { L("\n\n— Texte OCR extrait —\n") + $0.preview(600) } ?? ""))
            return
        }
        note = n
        if equipment != nil { showSelection = true; return }

        guard !n.purchaseOrders.isEmpty else {
            alert = InfoAlert(title: L("❌ Aucun PO détecté"),
                              message: L("La facture ne contient pas de numéro de PO exploitable.\n\n— OCR —\n") + (n.rawOcr ?? "").preview(500))
            return
        }
        let orders = (try? await app.repo.getPurchaseOrders()) ?? []
        var text = L("Fournisseur : %@\n\n%@ PO détectés :\n\n", String(describing: n.supplier ?? "?"), String(describing: n.purchaseOrders.count))
        matchedPO = nil
        for b in n.purchaseOrders {
            let id = DeliveryNoteParser.shared.extractIntFromPoNumber(s: b.number)?.int64Value
            let match = orders.first { $0.id == id }
            if matchedPO == nil, let m = match { matchedPO = m }
            text += "• \(b.number) " + (match != nil ? L("✅ en base") : L("❓ inconnu")) + "\n    \(b.summary.isEmpty ? L("(pas de desc)") : b.summary)\n"
        }
        status = L("📄 Facture analysée")
        resultText = text
    }

    private func validate(_ po: PurchaseOrder) async {
        guard let r = try? await app.repo.validatePurchaseReception(po: po, noteSupplier: note?.supplier,
                                                                     detectedPoCount: Int32(note?.purchaseOrders.count ?? 0)),
              r.success else {
            alert = InfoAlert(title: L("⛔ Permission refusée"), message: L("Rôle ADMIN / MANAGER requis.")); return
        }
        app.dataChanged()
        NotificationService.show(L("StockIT — Reception validee"), r.detail ?? "")
        success = (r.detail ?? "") + L("\n\nStatut passé à \"Reçu\".")
    }
}

struct POSelectionView: View {
    @EnvironmentObject var app: AppState
    let equipmentName: String?
    let note: ParsedNote
    let onConfirm: (SelectedPO) -> Void
    let onSkip: () -> Void
    @State private var selected: Int?
    @State private var knownIds: Set<Int64> = []
    @State private var warn = false

    var body: some View {
        List {
            Section {
                Text(L("🖥️ Équipement scanné : %@", String(describing: equipmentName ?? "?")) + (note.supplier.map { L("   •   Fournisseur facture : %@", String(describing: $0)) } ?? ""))
                    .font(.subheadline)
            }
            if note.purchaseOrders.isEmpty {
                EmptyStateView(icon: "doc.questionmark", title: L("❌ Aucun PO détecté"),
                               subtitle: L("La facture n'a produit aucun numéro de PO exploitable."))
            }
            ForEach(Array(note.purchaseOrders.enumerated()), id: \.offset) { i, b in
                Button { selected = i } label: {
                    HStack(alignment: .top) {
                        Image(systemName: selected == i ? "largecircle.fill.circle" : "circle").foregroundColor(.vistaBlue)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(b.number).font(.headline)
                            Text(b.summary.isEmpty ? L("(pas de description)") : b.summary).font(.subheadline).foregroundColor(.secondary)
                            badge(b)
                        }
                    }
                }
                .foregroundColor(.primary)
            }
        }
        .navigationTitle("\(note.purchaseOrders.count) PO détecté(s)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("Passer"), action: onSkip) }
            ToolbarItem(placement: .confirmationAction) { Button("✅ Confirmer") { confirm() }.disabled(selected == nil) }
        }
        .task {
            knownIds = Set(((try? await app.repo.getPurchaseOrders()) ?? []).map { $0.id })
        }
        .alert(L("⚠️ Incohérence possible"), isPresented: $warn) {
            Button("Oui, rattacher") { finish() }
            Button("Non, choisir un autre", role: .cancel) {}
        } message: {
            if let i = selected {
                let b = note.purchaseOrders[i]
                Text(L("Tu as scanné : « %@ ».\n\nMais le PO %@ indique :\n« %@ »\n\nCe PO ne mentionne pas ton équipement.\nConfirmer quand même ce rattachement ?", String(describing: equipmentName ?? ""), String(describing: b.number), String(describing: b.summary.isEmpty ? L("(sans description)") : b.summary)))
            }
        }
    }

    private func semantic(_ b: POBlock) -> Bool {
        guard let e = equipmentName?.lowercased(), !e.isEmpty else { return false }
        return b.summary.lowercased().contains(e)
    }

    @ViewBuilder private func badge(_ b: POBlock) -> some View {
        let inDb = DeliveryNoteParser.shared.extractIntFromPoNumber(s: b.number).map { knownIds.contains($0.int64Value) } ?? false
        let sem = semantic(b)
        let parts = [inDb ? L("✅ En base") : nil, sem ? L("🎯 Correspond au scan") : nil].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: "  •  ")).font(.caption.bold())
                .foregroundColor(sem ? Color(hex: 0x2E7D32) : Color(hex: 0x1565C0))
        }
    }

    private func confirm() {
        guard let i = selected else { return }
        if equipmentName == nil || equipmentName!.isEmpty || semantic(note.purchaseOrders[i]) { finish() } else { warn = true }
    }

    private func finish() {
        guard let i = selected else { return }
        let b = note.purchaseOrders[i]
        onConfirm(SelectedPO(number: b.number, description: b.summary, supplier: note.supplier))
    }
}

// MARK: - PackageLabelActivity

struct PackageLabelView: View {
    let expectedPo: String?
    var embedded: Bool = false
    let onUse: (PackageLabel) -> Void
    var onSkip: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraService()
    @State private var status = ""
    @State private var busy = false
    @State private var label: PackageLabel?
    @State private var alert: InfoAlert?

    var body: some View {
        CameraScreen(camera: camera, status: status, busy: busy, captureTitle: L("📷 Capturer étiquette"), cancelTitle: L("Passer"),
                     onCapture: { Task { await capture() } }, onCancel: { skip() },
                     onPickPhoto: { data in Task { await process(data) } }) { EmptyView() }
            .onAppear {
                camera.quality = .quality
                status = expectedPo.map { L("🏷️ Étiquette du carton (PO attendu : %@)", String(describing: $0)) } ?? L("🏷️ Cadre l'étiquette du carton puis capture.")
                camera.start()
            }
            .onDisappear { camera.stop() }
            .alert(item: $alert) { Alert(title: Text($0.title), message: Text($0.message)) }
            .alert(title, isPresented: Binding(get: { label != nil }, set: { if !$0 { label = nil } })) {
                Button(mismatch ? L("Oui, valider") : L("✅ Utiliser")) {
                    if let l = label { camera.stop(); if !embedded { dismiss() }; onUse(l) }
                }
                Button(mismatch ? L("Non, re-scanner") : L("Re-scanner"), role: .cancel) {}
            } message: { Text(recap) }
    }

    private var mismatch: Bool { PackageLabelParser.shared.poMismatch(expected: expectedPo, onLabel: label?.poOnLabel) }
    private var title: String { mismatch ? L("⚠️ PO facture ≠ PO étiquette") : L("🏷️ Étiquette lue") }

    private var recap: String {
        guard let l = label else { return "" }
        var s = L("• Produit : %@\n• Quantité : %@", String(describing: l.productName ?? "?"), String(describing: l.quantity))
        if let b = l.brand { s += L("\n• Marque : %@", String(describing: b)) }
        if let a = l.articleNumber { s += L("\n• Art.-No. : %@", String(describing: a)) }
        if !l.serials.isEmpty { s += L("\n• N° de série : ") + l.serials.prefix(5).joined(separator: ", ") + (l.serials.count > 5 ? " (+\(l.serials.count - 5))" : "") }
        if let p = l.poOnLabel { s += L("\n• PO étiquette : %@", String(describing: p)) }
        if mismatch { s += L("\n\nAttendu (facture) : %@\nDétecté (étiquette) : %@\n\nContinuer quand même ?", String(describing: expectedPo ?? ""), String(describing: l.poOnLabel ?? "")) }
        return s
    }

    private func skip() {
        camera.stop()
        if embedded { onSkip?() } else { dismiss() }
    }

    private func capture() async {
        guard let data = try? await camera.capturePhoto() else { status = L("❌ Capture échouée"); return }
        await process(data)
    }

    private func process(_ data: Data) async {
        busy = true; defer { busy = false }
        guard let img = UIImage(data: data)?.normalized() else {
            status = L("❌ Décodage image échoué"); return
        }
        status = L("🧠 [1/2] Lecture de l'étiquette…")
        let ocr = await VisionService.recognizeText(img)
        status = L("⏳ [2/2] Analyse par Claude (%@ caractères)…", String(describing: ocr?.count ?? 0))
        guard let l = try? await PackageLabelParser.shared.parseOcr(ocrText: ocr) else { return }
        if let e = l.error {
            alert = InfoAlert(title: L("❌ Étiquette carton"),
                              message: L("Extraction IA impossible.\nErreur : %@", String(describing: e)) + (l.rawOcr.map { L("\n\n— OCR extrait —\n") + $0.preview(500) } ?? ""))
            return
        }
        status = L("🏷️ Étiquette lue")
        label = l
    }
}

// MARK: - BatchScanActivity

struct BatchScanView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraService()
    @State private var codes: [String] = []
    @State private var boxes: [CGRect] = []

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(camera: camera).ignoresSafeArea()
            VStack {
                Text("Codes détectés : \(codes.count)")
                    .font(.headline).padding(10).background(.regularMaterial, in: Capsule()).padding(.top, 40)
                Spacer()
                if !codes.isEmpty {
                    ScrollView { VStack(alignment: .leading) { ForEach(codes, id: \.self) { Text($0).font(.caption.monospaced()) } } }
                        .frame(maxHeight: 120).padding(8).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
                HStack {
                    Button(L("Annuler")) { dismiss() }.buttonStyle(PrimaryButtonStyle(color: .vistaNavyMuted))
                    Button("VALIDER LE SCAN EN RAFALE") { Task { await finish() } }.buttonStyle(PrimaryButtonStyle(color: .vistaMint))
                }
                .padding()
            }
        }
        .onAppear {
            camera.onBarcodes = { found in
                for (v, _) in found where !codes.contains(v) {
                    codes.append(v)
                    Haptics.tap()
                }
            }
            camera.start(barcodes: true)
        }
        .onDisappear { camera.stop() }
    }

    private func finish() async {
        var created = 0, updated = 0
        for c in codes {
            switch try? await app.repo.addOrIncrementByCode(code: c) {
            case "created": created += 1
            case "updated": updated += 1
            default: break
            }
        }
        app.dataChanged()
        if !codes.isEmpty {
            if created + updated == 0 {
                app.show(L("Permission refusée (ADMIN/MANAGER requis)"), style: .urgent)
            } else {
                app.show(L("%@ nouveau(x) article(s), %@ quantité(s) +1", String(describing: created), String(describing: updated)), style: .success)
            }
        }
        dismiss()
    }
}

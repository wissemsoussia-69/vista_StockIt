import SwiftUI
import Shared

/// Inventaire (layout_stock + ProductAdapter) : recherche, +/−, détails.
struct StockListView: View {
    @EnvironmentObject var app: AppState
    @State private var products: [Product] = []
    @State private var query = ""
    @State private var selected: Product?
    @State private var showAdd = false

    var body: some View {
        List {
            if JiraAssetsClient.shared.isConfigured {
                Section {
                    HStack {
                        Image(systemName: "arrow.triangle.2.circlepath").foregroundColor(.vistaBlue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Synchronisé avec Jira Assets").font(.subheadline.bold())
                            Text(verbatim: syncLabel).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        if app.syncing { ProgressView() } else {
                            Button("Synchroniser") { Task { await app.syncAssets(silent: false) } }
                                .font(.caption.bold())
                                .accessibilityIdentifier("syncAssets")
                        }
                    }
                }
            }
            if products.isEmpty {
                EmptyStateView(icon: "shippingbox", title: L("Aucun matériel trouvé."), subtitle: L("Ajoutez un article ou scannez un équipement."))
            }
            ForEach(products) { p in
                ProductRow(product: p,
                           onDelta: { d in Task { await adjust(p, d) } },
                           onTap: { selected = p })
            }
            .onDelete(perform: delete)
            .deleteDisabled(!app.isAdmin)
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Inventaire")
        .searchable(text: $query, prompt: "Rechercher par Nom, Tag ou Catégorie…")
        .onChange(of: query) { _ in Task { await load() } }
        .refreshable { await app.syncAssets(silent: false); await load() }
        .task(id: app.dataVersion) { await load() }
        .toolbar {
            if app.canEditStock {
                Button { showAdd = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(item: $selected) { p in NavigationStack { ProductDetailView(product: p) } }
        .sheet(isPresented: $showAdd) { NavigationStack { ProductFormView(product: nil) } }
    }

    private var syncLabel: String {
        guard let d = app.lastSync else { return L("Tirez vers le bas pour synchroniser") }
        let f = DateFormatter(); f.locale = AppLanguage.current.locale; f.timeStyle = .short; f.dateStyle = .none
        return L("Dernière synchronisation : %@", f.string(from: d))
    }

    private func load() async {
        products = (try? await (query.isEmpty ? app.repo.getStock() : app.repo.searchProducts(query: query))) ?? []
    }

    private func adjust(_ p: Product, _ d: Int32) async {
        guard (try? await app.repo.updateQuantity(product: p, delta: d, syncAssets: true)) != nil else {
            app.show(L("Connexion requise"), style: .urgent); return
        }
        app.dataChanged()
    }

    private func delete(_ idx: IndexSet) {
        let items = idx.map { products[$0] }
        Task {
            for p in items { _ = try? await app.repo.deleteProduct(p: p) }
            app.dataChanged()
        }
    }
}

struct ProductRow: View {
    let product: Product
    let onDelta: (Int32) -> Void
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(product.categoryEmoji).font(.title)
            VStack(alignment: .leading, spacing: 2) {
                Text(product.name).font(.headline)
                Text(verbatim: LV(product.description_ ?? product.category ?? "")).font(.caption).foregroundColor(.secondary).lineLimit(2)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            Spacer()
            HStack(spacing: 10) {
                Button { onDelta(-1) } label: { Image(systemName: "minus.circle.fill").font(.title3) }
                Text(product.isLowStock ? "\(product.quantity) ⚠️" : "\(product.quantity)")
                    .font(.headline).foregroundColor(product.isLowStock ? .vistaCoral : .primary)
                    .frame(minWidth: 36)
                Button { onDelta(1) } label: { Image(systemName: "plus.circle.fill").font(.title3) }
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
}

/// dialog_product_details : kit, frise chronologique, brouillon de commande IA, alerte n8n/Slack.
struct ProductDetailView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State var product: Product
    @State private var movements: [StockMovement] = []
    @State private var busy = false
    @State private var alert: InfoAlert?
    @State private var edit = false
    @State private var draftForecast: Forecast?

    var body: some View {
        List {
            Section {
                LabeledContent("Stock actuel", value: "\(product.quantity)")
                LabeledContent("Seuil minimum", value: "\(product.minThreshold)")
                if let c = product.category { LabeledContent("Catégorie", value: LV(c)) }
                if let t = product.assetTag, !t.isEmpty { LabeledContent("Asset tag", value: t) }
                LabeledContent("Prix unitaire", value: product.unitPrice.money)
                if let b = product.brand { LabeledContent("Marque", value: b) }
                if let a = product.articleNumber { LabeledContent("Art.-No.", value: a) }
                if let po = product.poNumber { LabeledContent("PO facture", value: po) }
                if let d = product.poDescription { LabeledContent("Description PO", value: d) }
                if let s = product.receivedFrom { LabeledContent("Fournisseur", value: s) }
                if let d = product.description_, !d.isEmpty { Text(verbatim: LV(d)).font(.subheadline).foregroundColor(.secondary) }
            }
            if product.requiresKit {
                Section { Text("⚠️ KIT : Câble HDMI + Alimentation").foregroundColor(.vistaAmber) }
            }
            Section("Actions") {
                if app.canEditStock { Button("🛒 BROUILLON DE COMMANDE IA") { Task { await orderDraft() } } }
                Button("🚨 ALERTE N8N / SLACK") { Task { await lowStockAlert() } }
                if app.canEditStock { Button("✏️ Modifier") { edit = true } }
            }
            Section("Frise chronologique") {
                if movements.isEmpty { Text("Aucun mouvement").foregroundColor(.secondary) }
                ForEach(movements) { MovementRow(m: $0) }
            }
        }
        .navigationTitle(product.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("Fermer") { dismiss() } }
        .overlay { if busy { ProgressView().padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .task(id: app.dataVersion) {
            movements = (try? await app.repo.getProductMovements(productId: product.id)) ?? []
            if let p = try? await app.repo.getProduct(id: product.id) { product = p }
        }
        .alert(item: $alert) { Alert(title: Text($0.title), message: Text($0.message)) }
        .sheet(isPresented: $edit) { NavigationStack { ProductFormView(product: product) } }
        .sheet(isPresented: Binding(get: { draftForecast != nil }, set: { if !$0 { draftForecast = nil } })) {
            if let f = draftForecast { OrderDraftView(forecast: f) { draftForecast = nil } }
        }
    }

    /// Prévision de ce produit (sans appel Claude : rapide) puis brouillon rédigé par Claude.
    private func orderDraft() async {
        busy = true; defer { busy = false }
        let all = (try? await app.repo.getDemandForecast(useAI: false)) ?? []
        draftForecast = all.first { $0.productId == product.id }
    }

    private func lowStockAlert() async {
        busy = true; defer { busy = false }
        let r = try? await app.repo.triggerLowStockAlert(p: product)
        app.show(L("Alerte envoyée !"), style: .success)
        if let r {
            alert = r.success
                ? InfoAlert(title: L("✅ Ticket Jira créé"), message: r.detail ?? "")
                : InfoAlert(title: L("❌ Erreur Jira"), message: r.detail ?? "")
        }
    }
}

/// Création / modification d'article (layout_article).
struct ProductFormView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    let product: Product?
    @State private var name = ""
    @State private var category = "Informatique"
    @State private var desc = ""
    @State private var tag = ""
    @State private var qty = ""
    @State private var price = ""
    @State private var threshold = "5"
    @State private var reason = ""
    @State private var categories: [ProductCategory] = []

    var body: some View {
        Form {
            Section("Article") {
                TextField("Nom", text: $name)
                Picker("Catégorie", selection: $category) {
                    Text(verbatim: LV("Informatique")).tag("Informatique")
                    ForEach(categories) { Text(verbatim: LV($0.name)).tag($0.name) }
                }
                TextField("Description", text: $desc)
                TextField("Asset tag", text: $tag)
            }
            Section("Quantités") {
                TextField("Quantité", text: $qty).keyboardType(.numberPad)
                TextField("Prix unitaire (€)", text: $price).keyboardType(.decimalPad)
                if product != nil { TextField("Seuil minimum", text: $threshold).keyboardType(.numberPad) }
                if product == nil { TextField("Motif (ex: Ajout manuel)", text: $reason) }
            }
        }
        .navigationTitle(product == nil ? L("Nouvel article") : L("Modifier"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Valider") { Task { await save() } } }
        }
        .task {
            categories = (try? await app.repo.getCategories()) ?? []
            if let p = product {
                name = p.name; category = p.category ?? "Informatique"; desc = p.description_ ?? ""
                tag = p.assetTag ?? ""; qty = "\(p.quantity)"; price = "\(p.unitPrice)"; threshold = "\(p.minThreshold)"
            }
        }
    }

    private func save() async {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty, let q = Int32(qty) else {
            app.show(L("Veuillez remplir le nom et la quantité"), style: .urgent); return
        }
        let pr = Double(price.replacingOccurrences(of: ",", with: ".")) ?? 0
        if let p = product {
            let updated = p.withEdits(name: name, category: category, desc: desc.nilIfEmpty, assetTag: tag.nilIfEmpty,
                                      quantity: q, unitPrice: pr, minThreshold: Int32(threshold) ?? 5)
            let ok = (try? await app.repo.updateProduct(p: updated))?.boolValue ?? false
            if !ok { app.show(L("Permission refusée"), style: .urgent); return }
        } else {
            let catId = categories.first(where: { $0.name == category })?.id
            let id = try? await app.repo.addProduct(
                name: name, category: category, categoryId: catId.map { KotlinLong(value: $0) },
                desc: desc.nilIfEmpty ?? "Saisie manuelle", assetTag: tag.nilIfEmpty ?? "ASSET-\(nowMillis())",
                quantity: q, unitPrice: pr, mfgDate: "", expDate: "", reason: reason.nilIfEmpty ?? "Ajout manuel",
                poNumber: nil, poDescription: nil, receivedFrom: nil, articleNumber: nil, brand: nil, packagePoNumber: nil,
                syncAssets: true)
            if id == nil { app.show(L("Permission refusée (ADMIN/MANAGER requis)"), style: .urgent); return }
            app.show(L("Article %@ ajouté avec succès !", String(describing: name)), style: .success)
        }
        app.dataChanged()
        dismiss()
    }
}

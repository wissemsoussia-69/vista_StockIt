import SwiftUI
import Shared

/// Confirmation après analyse : nom proposé par Claude (modifiable), produits du stock
/// correspondants, ou saisie manuelle si Claude n'a rien reconnu.
struct ConfirmAssetView: View {
    @EnvironmentObject var app: AppState
    let detected: String?
    let error: String?
    /// true : la sortie exige un produit existant en stock.
    let requireExisting: Bool
    /// Confiance de Claude 0-100 (-1 = inconnue). Sous 60 % : case « J'ai vérifié » obligatoire.
    var confidence: Int = -1
    /// Numéro de série lu par Claude sur la photo (modifiable).
    var serial: String? = nil
    let onConfirm: (_ name: String, _ existing: Product?) -> Void
    let onRetake: () -> Void
    /// Série confirmée par l'utilisateur (entrée de stock).
    var onSerial: ((String?) -> Void)? = nil

    @State private var name = ""
    @State private var candidates: [Product] = []
    @State private var all: [Product] = []
    @State private var selected: Product?
    @State private var search = ""
    @State private var verified = false
    @State private var serialText = ""

    private var needsCheck: Bool { Confidence.shared.requiresManualCheck(c: Int32(confidence)) }

    private var list: [Product] {
        let base = search.isEmpty ? candidates + all.filter { p in !candidates.contains { $0.id == p.id } } : all
        let filtered = search.isEmpty ? base : ProductMatcher.shared.candidates(detected: search, stock: base, onlyInStock: false)
            + base.filter { ProductMatcher.shared.normalize(s: $0.name).contains(ProductMatcher.shared.normalize(s: search)) }
        var seen = Set<Int64>()
        return filtered.filter { (!requireExisting || $0.quantity > 0) && seen.insert($0.id).inserted }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let d = detected {
                    Section("Claude a identifié") {
                        Label(d, systemImage: "sparkles").font(.headline)
                        ConfidenceBadge(value: confidence)
                        if needsCheck {
                            Text("⚠️ Confiance faible : vérifie le nom (et le produit) avant de valider.")
                                .font(.caption).foregroundColor(.vistaCoral)
                            Toggle("J'ai vérifié l'équipement", isOn: $verified)
                                .accessibilityIdentifier("verifiedToggle")
                        }
                    }
                } else {
                    Section {
                        Label(error ?? L("Équipement non reconnu"), systemImage: "exclamationmark.triangle.fill")
                            .foregroundColor(.vistaCoral)
                        Text(verbatim: requireExisting ? L("Choisis le produit dans la liste ci-dessous.")
                                             : L("Tape le nom de l'équipement ou choisis un produit existant."))
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                if !requireExisting {
                    Section("Nom enregistré") {
                        TextField("ex: Souris Logitech MX Master 3S", text: $name)
                            .onChange(of: name) { _ in if selected?.name != name { selected = nil } }
                            .accessibilityIdentifier("assetName")
                    }
                    Section {
                        TextField("Numéro de série (S/N)", text: $serialText)
                            .textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .accessibilityIdentifier("serialField")
                    } header: {
                        Text("Numéro de série")
                    } footer: {
                        Text(verbatim: serial != nil ? L("🧠 Lu par Claude sur la photo — corrige-le si besoin. Il sera envoyé à Jira Assets.")
                                           : L("Optionnel : il sera aussi recherché sur l'étiquette du carton."))
                    }
                }
                Section(header: Text(verbatim: requireExisting ? L("Produit à sortir") : L("Ou rattacher à un produit existant"))) {
                    TextField("Rechercher dans le stock…", text: $search)
                    if list.isEmpty { Text("Aucun produit").foregroundColor(.secondary) }
                    ForEach(list.prefix(30)) { p in
                        Button {
                            selected = p
                            name = p.name
                        } label: {
                            HStack {
                                Image(systemName: selected?.id == p.id ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(.vistaBlue)
                                Text(p.categoryEmoji)
                                VStack(alignment: .leading) {
                                    Text(p.name).foregroundColor(.primary)
                                    Text("Stock : \(p.quantity)").font(.caption).foregroundColor(.secondary)
                                }
                                if candidates.first?.id == p.id { Spacer(); Pill(text: L("Suggéré"), color: .vistaMint, foreground: .vistaNavy) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Confirmer l'équipement")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Reprendre la photo", action: onRetake) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Valider") {
                        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
                        let s = serialText.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSerial?(s.isEmpty ? nil : AIGateway.shared.normalizeSerial(raw: s) ?? s.uppercased())
                        onConfirm(selected?.name ?? n, selected)
                    }
                    .disabled((requireExisting ? selected == nil : name.trimmingCharacters(in: .whitespaces).isEmpty)
                              || (needsCheck && !verified))
                }
            }
            .task {
                serialText = serial ?? ""
                all = (try? await app.repo.getStock()) ?? []
                if let d = detected {
                    name = d
                    candidates = ProductMatcher.shared.candidates(detected: d, stock: all, onlyInStock: requireExisting)
                    if let best = ProductMatcher.shared.bestMatch(detected: d, stock: all, onlyInStock: requireExisting) {
                        selected = best
                        if requireExisting { name = best.name }
                    }
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

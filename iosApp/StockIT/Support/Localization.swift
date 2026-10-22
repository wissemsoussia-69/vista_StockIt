import SwiftUI
import Shared

/// Langue de l'application (choisie dans Paramètres), indépendante de la langue de l'iPhone.
/// Le changement s'applique immédiatement : l'environnement `locale` de SwiftUI est mis à jour
/// (tous les `Text("…")` sont retraduits) et `AppLanguage.bundle` fournit les textes construits en code.
enum AppLanguage: String, CaseIterable, Identifiable {
    case fr, en
    var id: String { rawValue }

    static let storageKey = "appLanguage"

    /// Nom affiché dans le sélecteur (toujours dans sa propre langue).
    var displayName: String {
        switch self {
        case .fr: return "Français"
        case .en: return "English"
        }
    }

    var locale: Locale { Locale(identifier: rawValue) }

    /// Langue courante (UserDefaults ; par défaut : langue de l'iPhone si anglais, sinon français).
    static var current: AppLanguage {
        if let raw = UserDefaults.standard.string(forKey: storageKey), let l = AppLanguage(rawValue: raw) { return l }
        return Locale.preferredLanguages.first?.hasPrefix("en") == true ? .en : .fr
    }

    /// Bundle de la langue courante (pour `L(…)`).
    static var bundle: Bundle {
        if let path = Bundle.main.path(forResource: current.rawValue, ofType: "lproj"), let b = Bundle(path: path) { return b }
        return .main
    }

    /// Propage la langue au module partagé (textes Kotlin + langue de réponse de Claude).
    static func applyToShared() {
        StockItConfig.shared.language = current.rawValue
        UserDefaults(suiteName: WidgetBridge.suite)?.set(current.rawValue, forKey: storageKey)
    }
}

/// Traduit une valeur stockée en base (statut, priorité…) pour l'affichage uniquement.
/// La valeur enregistrée ne change pas (ex. « Reçu » reste « Reçu » en base).
func LV(_ stored: String?) -> String {
    guard let v = stored, !v.isEmpty else { return stored ?? "" }
    if AppLanguage.current == .fr { return v }
    let direct = L(v)
    if direct != v { return direct }
    // Motifs composés enregistrés en base : « Sortie sur ticket SD-1 — Sortie libre », « Nouvel article ajouté [PO X] »…
    var out = v
    for (fr, en) in storedPhrases { out = out.replacingOccurrences(of: fr, with: en) }
    return out
}

/// Expressions françaises stockées en base, traduites uniquement à l'affichage.
private var storedPhrases: [(String, String)] {
    [("Sortie sur ticket", "Check-out on ticket"), ("Sortie libre", "Free check-out"), ("Choix manuel", "Manual choice"),
     ("Nouvel article ajouté", "New item added"), ("Ajustement manuel", "Manual adjustment"), ("Article supprimé", "Item deleted"),
     ("Kit complet (Alim + HDMI)", "Complete kit (Power + HDMI)"), ("Scan IA StockIT PFE", "StockIT AI scan"),
     ("Saisie manuelle", "Manual entry"), ("Ajout manuel", "Manual entry"), ("Réception", "Receiving"), ("Prêt", "Loan"),
     ("Arrivée stock", "Stock arrival"), ("Sortie pour bureau", "Out to office"), ("Système", "System"),
     ("Scan IA", "AI scan"), ("Saisie manuelle", "Manual entry")]
}

/// Texte traduit dans la langue de l'app (pour les textes construits en code : statuts, alertes, toasts…).
/// `L("Capture échouée")` ; avec arguments : `L("%@ ajouté au stock", name)`.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = AppLanguage.bundle.localizedString(forKey: key, value: key, table: nil)
    return args.isEmpty ? format : String(format: format, locale: AppLanguage.current.locale, arguments: args)
}

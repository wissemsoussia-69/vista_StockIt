import XCTest

/// Parcours complet des fonctions (mode démo admin) + sécurité de session.
final class FullTests: XCTestCase {
    var app: XCUIApplication!
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }
    func dismissSystemAlerts() {
        for l in ["Autoriser", "Allow"] where springboard.buttons[l].waitForExistence(timeout: 2) { springboard.buttons[l].tap() }
    }
    func login() {
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]
        pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()
        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))
    }
    func back() { app.navigationBars.buttons.element(boundBy: 0).tap() }
    func openMore(_ label: String) {
        app.tabBars.buttons["Plus"].tap()
        var tries = 0
        while !app.staticTexts[label].isHittable && tries < 4 { app.swipeUp(); tries += 1 }
        app.staticTexts[label].tap()
    }

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launch()
        dismissSystemAlerts()
    }

    func test1_SessionLockedWhenLeavingApp() {
        login()
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        XCTAssertTrue(app.buttons["Se connecter avec Vista SSO"].waitForExistence(timeout: 10),
                      "Après avoir quitté l'app, l'écran de connexion doit réapparaître")
        shot("lock_after_background")
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["Se connecter avec Vista SSO"].waitForExistence(timeout: 10),
                      "Au relancement, aucune session ne doit être restaurée")
    }

    func test2_StockActions() {
        login()
        app.tabBars.buttons["Stock"].tap()
        // + / − sur le premier produit
        let plus = app.buttons["Ajouter"].firstMatch
        _ = plus
        app.buttons.matching(identifier: "plus.circle.fill").firstMatch.tap()
        sleep(1)
        app.buttons.matching(identifier: "minus.circle.fill").firstMatch.tap()
        sleep(1)
        // Nouvel article
        app.navigationBars["Inventaire"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.navigationBars["Nouvel article"].waitForExistence(timeout: 5))
        let name = app.textFields["Nom"]; name.tap(); name.typeText("Clavier Test iOS")
        let q = app.textFields["Quantité"]; q.tap(); q.typeText("3")
        let pr = app.textFields["Prix unitaire (€)"]; pr.tap(); pr.typeText("25")
        app.buttons["Valider"].tap()
        XCTAssertTrue(app.staticTexts["Clavier Test iOS"].waitForExistence(timeout: 8))
        shot("stock_after_add")
        // Recherche
        app.swipeDown()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("Clavier")
        sleep(1); shot("stock_search")
        XCTAssertTrue(app.staticTexts["Clavier Test iOS"].exists)
        let cancel = app.buttons["Cancel"].exists ? app.buttons["Cancel"] : app.buttons["Annuler"]
        if cancel.exists { cancel.firstMatch.tap() } else { app.searchFields.firstMatch.buttons.firstMatch.tap() }
        // Fiche + modification
        app.staticTexts["Clavier Test iOS"].firstMatch.tap()
        XCTAssertTrue(app.buttons["✏️ Modifier"].waitForExistence(timeout: 5))
        app.buttons["✏️ Modifier"].tap()
        XCTAssertTrue(app.navigationBars["Modifier"].waitForExistence(timeout: 5))
        app.buttons["Valider"].tap()
        sleep(1)
        app.buttons["🛒 BROUILLON DE COMMANDE IA"].tap()
        sleep(3); shot("order_draft")
        if app.buttons["Annuler"].firstMatch.exists { app.buttons["Annuler"].firstMatch.tap() }
        app.buttons["Fermer"].firstMatch.tap()
        // Dashboard reflète le mouvement
        app.tabBars.buttons["Dashboard"].tap()
        sleep(2); shot("dashboard_after")
    }

    func test3_MoreScreens() {
        login()
        openMore("Catégories"); sleep(1)
        app.navigationBars["Catégories"].buttons.element(boundBy: 1).tap()
        let alertName = app.alerts.textFields["Nom"]
        if alertName.waitForExistence(timeout: 3) { alertName.typeText("Casques"); app.alerts.buttons["Enregistrer"].tap() }
        XCTAssertTrue(app.staticTexts["Casques"].waitForExistence(timeout: 5)); shot("categories"); back()

        openMore("Fournisseurs"); sleep(1)
        XCTAssertTrue(app.staticTexts["Dell France"].waitForExistence(timeout: 5)); shot("suppliers"); back()

        openMore("Commandes"); sleep(1)
        app.navigationBars["Commandes"].buttons.element(boundBy: 1).tap()
        let p = app.alerts.textFields["Produit"]
        if p.waitForExistence(timeout: 3) { p.typeText("Ecran Dell 27"); app.alerts.buttons["Créer"].tap() }
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Ecran Dell 27'")).firstMatch.waitForExistence(timeout: 5))
        shot("purchase"); back()

        openMore("Expéditions"); sleep(1)
        XCTAssertTrue(app.staticTexts["Client Vistaprint"].waitForExistence(timeout: 5)); shot("shipping"); back()

        openMore("Rapports"); sleep(2); shot("reports"); back()
        openMore("IA Optimisation"); sleep(2); shot("insights"); back()
        openMore("Classement / Leaderboard"); sleep(1); shot("leaderboard"); back()
        openMore("Audit Actions"); sleep(1)
        XCTAssertTrue(app.staticTexts["LOGIN"].firstMatch.waitForExistence(timeout: 5)); shot("audit"); back()
        openMore("Utilisateurs"); sleep(1)
        XCTAssertTrue(app.staticTexts["Nadhem"].waitForExistence(timeout: 5)); shot("users"); back()

        openMore("Réclamation"); sleep(1)
        app.navigationBars["Centre de Réclamations"].buttons.element(boundBy: 1).tap()
        let subj = app.textFields["Sujet (ex: PC en panne)"]
        XCTAssertTrue(subj.waitForExistence(timeout: 5)); subj.tap(); subj.typeText("PC lent test")
        app.buttons["Envoyer"].tap()
        XCTAssertTrue(app.staticTexts["PC lent test"].waitForExistence(timeout: 8)); shot("claims"); back()

        openMore("Rapport mensuel IA (PDF)"); sleep(1)
        app.buttons["Générer le rapport mensuel"].tap()
        sleep(8); shot("pdf"); back()

        openMore("Assistant IA Vista"); sleep(1)
        let input = app.textFields["Tapez votre message..."]
        if input.waitForExistence(timeout: 5) { input.tap(); input.typeText("Combien de laptops ?"); app.buttons["paperplane.fill"].firstMatch.tap() }
        sleep(8); shot("ai_chat"); back()

        openMore("Paramètres"); sleep(1); shot("settings"); back()

        // Menu scan
        app.tabBars.buttons["Scanner"].tap()
        XCTAssertTrue(app.buttons["🧠 Scanner un équipement (IA)"].waitForExistence(timeout: 5)); shot("scan_menu")
        app.buttons["📤 Sortie équipement"].tap()
        sleep(3); shot("scan_out_camera")
        app.buttons["Annuler"].firstMatch.tap()

        // Tickets
        app.tabBars.buttons["Tickets"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'SD-'")).firstMatch.waitForExistence(timeout: 20))
        app.buttons["Highest"].tap(); sleep(1); shot("tickets_highest")

        // Déconnexion
        openMore("Déconnexion")
        XCTAssertTrue(app.buttons["Se connecter avec Vista SSO"].waitForExistence(timeout: 10))
    }
}

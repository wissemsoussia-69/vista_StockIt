import XCTest

/// Choix de la langue (Paramètres) : passage FR → EN → FR, vérification que l'interface suit.
final class LanguageTests: XCTestCase {
    var app: XCUIApplication!
    func shot(_ n: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = n; a.lifetime = .keepAlways; add(a) }

    /// Dump de tous les textes visibles (pour repérer d'éventuels restes en français).
    func dump(_ n: String) {
        let labels = app.descendants(matching: .any).allElementsBoundByIndex.prefix(400).map { $0.label }.filter { !$0.isEmpty }
        let a = XCTAttachment(string: labels.joined(separator: "\n")); a.name = "texts_" + n; a.lifetime = .keepAlways; add(a)
    }

    func choose(_ lang: String) {
        app.tabBars.buttons.element(boundBy: 4).tap()
        var n = 0
        let settings = app.staticTexts[lang == "en" ? "Paramètres" : "Settings"]
        while !settings.isHittable && n < 4 { app.swipeUp(); n += 1 }
        settings.tap()
        let picker = app.buttons["languagePicker"].exists ? app.buttons["languagePicker"] : app.otherElements["languagePicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        let target = lang == "en" ? "English" : "Français"
        for _ in 0..<3 {
            picker.tap()
            // Option du menu contextuel (bouton dans le menu, pas la ligne du formulaire)
            let item = app.collectionViews.buttons[target].exists ? app.collectionViews.buttons[target] : app.buttons.matching(identifier: target).element(boundBy: app.buttons.matching(identifier: target).count - 1)
            if item.waitForExistence(timeout: 3) { item.tap(); break }
        }
        sleep(1)
    }

    func testSwitchLanguage() {
        app = XCUIApplication()
        app.launchEnvironment["STOCKIT_RESET_LANGUAGE"] = "fr"   // départ toujours en français
        app.launch()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Autoriser", "Allow"] where sb.buttons[l].waitForExistence(timeout: 2) { sb.buttons[l].tap() }
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]; pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()
        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))

        // FR → EN
        choose("en")
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5), "Le titre doit passer en anglais immédiatement")
        XCTAssertTrue(app.staticTexts["Dark mode"].exists)
        XCTAssertTrue(app.staticTexts["Language"].exists)
        shot("en_settings"); dump("en_settings")

        // Tous les onglets en anglais
        app.tabBars.buttons["Dashboard"].tap()
        XCTAssertTrue(app.navigationBars["Dashboard"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Quick actions"].exists)
        sleep(1); shot("en_dashboard"); dump("en_dashboard")

        app.tabBars.buttons["Stock"].tap()
        XCTAssertTrue(app.navigationBars["Inventory"].waitForExistence(timeout: 5))
        shot("en_stock")

        app.tabBars.buttons["Tickets"].tap()
        XCTAssertTrue(app.navigationBars["Open Jira tickets"].waitForExistence(timeout: 10))
        sleep(2); shot("en_tickets")

        app.tabBars.buttons["Scan"].tap()
        XCTAssertTrue(app.buttons["🧠 Scan equipment (AI)"].waitForExistence(timeout: 5))
        shot("en_scan_menu")
        (app.buttons["Cancel"].exists ? app.buttons["Cancel"] : app.otherElements["PopoverDismissRegion"]).firstMatch.tap()

        app.tabBars.buttons["More"].tap()
        if app.navigationBars["Settings"].exists { app.navigationBars.buttons.element(boundBy: 0).tap() }
        XCTAssertTrue(app.navigationBars["More"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Suppliers"].exists)
        shot("en_more"); dump("en_more")
        app.staticTexts["AI Optimization"].tap()
        sleep(2); shot("en_insights"); dump("en_insights")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.staticTexts["Vista AI Assistant"].tap()
        XCTAssertTrue(app.staticTexts["Hello! I'm your StockIT assistant. How can I help you today?"].waitForExistence(timeout: 5))
        let input = app.textFields["Type your message..."]
        input.tap(); input.typeText("How many laptops?")
        app.buttons["paperplane.fill"].firstMatch.tap()
        sleep(15); shot("en_ai_chat"); dump("en_ai_chat")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // EN → FR
        choose("fr")
        XCTAssertTrue(app.navigationBars["Paramètres"].waitForExistence(timeout: 5), "Retour au français")
        XCTAssertTrue(app.staticTexts["Mode sombre"].exists)
        shot("fr_settings")
    }
}

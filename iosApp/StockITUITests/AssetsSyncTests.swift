import XCTest

/// Inventaire = Jira Assets : après connexion, la liste contient les modèles réels (lecture seule côté Jira).
final class AssetsSyncTests: XCTestCase {
    func testInventoryMatchesJiraAssets() {
        let app = XCUIApplication()
        app.launchEnvironment["STOCKIT_RESET_LANGUAGE"] = "fr"
        app.launch()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Autoriser", "Allow"] where sb.buttons[l].waitForExistence(timeout: 2) { sb.buttons[l].tap() }
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]; pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()
        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))
        app.tabBars.buttons["Stock"].tap()
        let sync = app.buttons["syncAssets"]
        XCTAssertTrue(sync.waitForExistence(timeout: 30), "Bouton Synchroniser")
        sync.tap()
        XCTAssertTrue(app.staticTexts["Apple Magic Mouse (USB-C)"].waitForExistence(timeout: 60), "Modèle Jira présent")
        let search = app.searchFields.firstMatch
        app.swipeDown()
        if search.waitForExistence(timeout: 5) { search.tap(); search.typeText("EPOS") }
        XCTAssertTrue(app.staticTexts["EPOS SC 160"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["EPOS IMPACT 100"].exists)
        XCTAssertTrue(app.staticTexts["20"].exists || app.staticTexts["20 ⚠️"].exists, "20 × EPOS SC 160")
        XCTAssertFalse(app.staticTexts["Laptop Dell Latitude"].exists, "Données de démo supprimées")
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = "inventory_synced"; a.lifetime = .keepAlways; add(a)
        let t = XCTAttachment(string: app.staticTexts.allElementsBoundByIndex.map { $0.label }.joined(separator: "\n")); t.name = "inventory_texts"; t.lifetime = .keepAlways; add(t)
    }
}

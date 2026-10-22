import XCTest

/// Test de fumée : connexion locale (admin/admin123) puis parcours des onglets principaux.
final class SmokeTests: XCTestCase {
    func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func testLoginAndNavigate() {
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "notif") { alert in
            if alert.buttons["Autoriser"].exists { alert.buttons["Autoriser"].tap(); return true }
            if alert.buttons["Allow"].exists { alert.buttons["Allow"].tap(); return true }
            return false
        }
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Autoriser", "Allow"] where springboard.buttons[label].waitForExistence(timeout: 3) {
            springboard.buttons[label].tap()
        }
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        shot(app, "01_login")
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]
        pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()

        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))
        sleep(2); shot(app, "02_dashboard")

        app.tabBars.buttons["Stock"].tap()
        XCTAssertTrue(app.navigationBars["Inventaire"].waitForExistence(timeout: 10))
        sleep(1); shot(app, "03_stock")
        app.staticTexts["Laptop Dell Latitude"].firstMatch.tap()
        sleep(2); shot(app, "04_detail")
        app.buttons["Fermer"].firstMatch.tap()

        app.tabBars.buttons["Plus"].tap()
        XCTAssertTrue(app.navigationBars["Plus"].waitForExistence(timeout: 10))
        shot(app, "05_plus")
        app.staticTexts["Rapports"].tap()
        sleep(2); shot(app, "06_rapports")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.staticTexts["IA Optimisation"].tap()
        sleep(2); shot(app, "07_ia")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.staticTexts["Fournisseurs"].tap()
        sleep(1); shot(app, "08_fournisseurs")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.swipeUp()
        app.staticTexts["Paramètres"].tap()
        sleep(1); shot(app, "09_parametres")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.tabBars.buttons["Tickets"].tap()
        sleep(3); shot(app, "10_tickets")
        app.tabBars.buttons["Scanner"].tap()
        sleep(1); shot(app, "11_scan_menu")
    }
}

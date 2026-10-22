import XCTest

/// « Tester Slack » (Plus ▸ Paramètres) : doit afficher ✅ (relais n8n → bot alerts → #etx-alerts).
final class SlackTests: XCTestCase {
    func testSlackButton() {
        let app = XCUIApplication()
        app.launch()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Autoriser", "Allow"] where sb.buttons[l].waitForExistence(timeout: 2) { sb.buttons[l].tap() }
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]; pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()
        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))
        app.tabBars.buttons["Plus"].tap()
        var n = 0; while !app.staticTexts["Paramètres"].isHittable && n < 4 { app.swipeUp(); n += 1 }
        app.staticTexts["Paramètres"].tap()
        let b = app.buttons["testSlack"]
        XCTAssertTrue(b.waitForExistence(timeout: 5))
        b.tap()
        let r = app.staticTexts["slackResult"]
        XCTAssertTrue(r.waitForExistence(timeout: 30))
        let a = XCTAttachment(screenshot: app.screenshot()); a.name = "slack_result"; a.lifetime = .keepAlways; add(a)
        XCTAssertTrue(r.label.hasPrefix("✅"), "Résultat : \(r.label)")
    }
}

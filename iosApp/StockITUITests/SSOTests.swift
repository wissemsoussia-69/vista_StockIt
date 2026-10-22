import XCTest

/// Vérifie que « Se connecter avec Vista SSO » ouvre bien la page de connexion Okta Vista.
final class SSOTests: XCTestCase {
    func testSSOOpensOkta() {
        let app = XCUIApplication()
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Autoriser", "Allow"] where springboard.buttons[l].waitForExistence(timeout: 2) { springboard.buttons[l].tap() }
        let sso = app.buttons["Se connecter avec Vista SSO"]
        XCTAssertTrue(sso.waitForExistence(timeout: 15))
        sso.tap()
        // Alerte système « StockIT souhaite utiliser <domaine Auth0> pour se connecter »
        for l in ["Continuer", "Continue"] where springboard.buttons[l].waitForExistence(timeout: 8) { springboard.buttons[l].tap() }
        sleep(12)
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = "sso_page"; a.lifetime = .keepAlways; add(a)
    }
}

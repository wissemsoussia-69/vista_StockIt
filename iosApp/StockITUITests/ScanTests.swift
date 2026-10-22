import XCTest

/// Scénarios de scan de bout en bout (mode test : aucun Jira/Slack/e-mail réel).
/// La photo vient de la galerie du simulateur et est analysée par Claude (clé Cimpress).
final class ScanTests: XCTestCase {
    var app: XCUIApplication!
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    func shot(_ n: String) { let a = XCTAttachment(screenshot: app.screenshot()); a.name = n; a.lifetime = .keepAlways; add(a) }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["STOCKIT_TEST_PHOTO"] = "/tmp/scan_mouse.jpg"
        setUpSession()
    }

    func setUpSession() {
        app.launch()
        for l in ["Autoriser", "Allow"] where springboard.buttons[l].waitForExistence(timeout: 2) { springboard.buttons[l].tap() }
        let user = app.textFields["Nom d'utilisateur"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields["Mot de passe"]; pwd.tap(); pwd.typeText("admin123")
        app.buttons["Connexion classique"].tap()
        XCTAssertTrue(app.navigationBars["Tableau de bord"].waitForExistence(timeout: 15))
        // Mode test ON
        app.tabBars.buttons["Plus"].tap()
        var n = 0; while !app.staticTexts["Paramètres"].isHittable && n < 4 { app.swipeUp(); n += 1 }
        app.staticTexts["Paramètres"].tap()
        let t = app.switches["testModeToggle"]
        XCTAssertTrue(t.waitForExistence(timeout: 5))
        if (t.value as? String) != "1" { t.switches.firstMatch.tap() }
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    func openScan(_ title: String) {
        let b = app.buttons[title]
        for _ in 0..<3 where !b.exists {
            app.tabBars.buttons["Scanner"].tap()
            _ = b.waitForExistence(timeout: 4)
        }
        XCTAssertTrue(b.exists, "Menu scan non affiché")
        b.tap()
        for l in ["Autoriser", "OK", "Allow"] where springboard.buttons[l].waitForExistence(timeout: 2) { springboard.buttons[l].tap() }
        XCTAssertTrue(app.buttons["pickPhoto"].waitForExistence(timeout: 15), "Écran caméra non ouvert")
    }

    /// Analyse la photo de test injectée (STOCKIT_TEST_PHOTO).
    func pickPhoto(_ index: Int) {
        let b = app.buttons["testPhoto"]
        XCTAssertTrue(b.waitForExistence(timeout: 10))
        b.tap()
    }

    func launch(photo: String) {
        app.terminate()
        app.launchEnvironment["STOCKIT_TEST_PHOTO"] = photo
        setUpSession()
    }

    /// Scan équipement : photo → Claude → confirmation → « Enregistrer sans PO » → enregistré.
    func test1_ScanAssetSavedWithoutInvoice() {
        openScan("🧠 Scanner un équipement (IA)")
        pickPhoto(0) // souris
        XCTAssertTrue(app.navigationBars["Confirmer l'équipement"].waitForExistence(timeout: 90), "Claude doit répondre")
        shot("scan1_confirm")
        let name = app.textFields["assetName"]
        XCTAssertTrue(name.exists)
        let v = (name.value as? String) ?? ""
        XCTAssertTrue(v.localizedCaseInsensitiveContains("souris") || v.localizedCaseInsensitiveContains("mouse"), "Nom proposé : \(v)")
        app.buttons["Valider"].tap()
        let skip = app.buttons["Enregistrer sans PO"]
        XCTAssertTrue(skip.waitForExistence(timeout: 8)); shot("scan1_ask_invoice")
        skip.tap()
        XCTAssertTrue(app.alerts["🟢 Équipement enregistré"].waitForExistence(timeout: 15)); shot("scan1_saved")
        app.alerts.buttons["OK"].tap()
    }

    /// Bug corrigé : « Passer » sur la facture puis l'étiquette enregistre quand même l'équipement.
    func test2_SkipInvoiceAndLabelStillSaves() {
        openScan("🧠 Scanner un équipement (IA)")
        pickPhoto(0)
        XCTAssertTrue(app.navigationBars["Confirmer l'équipement"].waitForExistence(timeout: 90))
        let name = app.textFields["assetName"]
        name.tap(); name.clearAndType("Casque Test Passer")
        app.buttons["Valider"].tap()
        XCTAssertTrue(app.buttons["📷 Scanner la facture"].waitForExistence(timeout: 8))
        app.buttons["📷 Scanner la facture"].tap()
        XCTAssertTrue(app.buttons["Passer"].waitForExistence(timeout: 10)); shot("scan2_invoice")
        app.buttons["Passer"].tap()
        XCTAssertTrue(app.buttons["📷 Scanner l'étiquette"].waitForExistence(timeout: 8)); shot("scan2_ask_label")
        app.buttons["📷 Scanner l'étiquette"].tap()
        XCTAssertTrue(app.buttons["Passer"].waitForExistence(timeout: 10))
        app.buttons["Passer"].tap()
        XCTAssertTrue(app.alerts["🟢 Équipement enregistré"].waitForExistence(timeout: 15)); shot("scan2_saved")
        app.alerts.buttons["OK"].tap()
        app.tabBars.buttons["Stock"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'Casque Test Passer'")).firstMatch.waitForExistence(timeout: 8))
    }

    /// Écran : kit incomplet → ticket Jira (simulé en mode test).
    func test3_MonitorKitIncomplete() {
        launch(photo: "/tmp/scan_monitor.jpg")
        openScan("🧠 Scanner un équipement (IA)")
        pickPhoto(1) // écran
        XCTAssertTrue(app.navigationBars["Confirmer l'équipement"].waitForExistence(timeout: 90))
        let name = app.textFields["assetName"]
        let v = (name.value as? String) ?? ""
        if !(v.localizedCaseInsensitiveContains("cran") || v.localizedCaseInsensitiveContains("onit")) {
            name.tap(); name.clearAndType("Écran Dell P2422H")
        }
        shot("scan3_confirm")
        app.buttons["Valider"].tap()
        XCTAssertTrue(app.switches["Câble HDMI"].waitForExistence(timeout: 8)); shot("scan3_kit")
        app.switches["Câble d'alimentation"].switches.firstMatch.tap()
        app.buttons["Valider"].tap()
        XCTAssertTrue(app.alerts["🟢 Ticket Jira créé"].waitForExistence(timeout: 20)); shot("scan3_jira")
        XCTAssertTrue(app.alerts.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Mode test'")).firstMatch.exists)
        app.alerts.buttons.firstMatch.tap()
    }

    /// Sortie : Claude « Souris … » → produit « Souris Logitech MX » trouvé → sortie sans ticket.
    func test4_ScanOutFindsProduct() {
        openScan("📤 Sortie équipement")
        pickPhoto(0)
        XCTAssertTrue(app.navigationBars["Confirmer l'équipement"].waitForExistence(timeout: 90))
        shot("scan4_confirm")
        XCTAssertTrue(app.buttons["Valider"].isEnabled, "Un produit du stock doit être présélectionné")
        app.buttons["Valider"].tap()
        let qty = app.textFields["ex: 5"]
        XCTAssertTrue(qty.waitForExistence(timeout: 8))
        qty.tap(); qty.typeText("1")
        app.buttons["Continuer"].tap()
        XCTAssertTrue(app.buttons["⏭️ Skip — sortir sans ticket"].waitForExistence(timeout: 5)); shot("scan4_method")
        app.buttons["⏭️ Skip — sortir sans ticket"].tap()
        XCTAssertTrue(app.staticTexts["🟢 Sortie enregistrée"].waitForExistence(timeout: 10)); shot("scan4_done")
        app.buttons["Terminer"].tap()
    }
}

extension XCUIElement {
    func clearAndType(_ text: String) {
        tap()
        // place le curseur à la fin puis efface tout
        coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
        let n = ((value as? String) ?? "").count + 10
        typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: n))
        typeText(text)
    }
}

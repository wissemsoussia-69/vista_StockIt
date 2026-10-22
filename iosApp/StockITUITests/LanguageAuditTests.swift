import XCTest

/// Audit de traduction : ouvre chaque écran en anglais et enregistre tous les textes visibles.
final class LanguageAuditTests: XCTestCase {
    var app: XCUIApplication!

    func dump(_ n: String) {
        sleep(2)
        var labels: [String] = []
        for t in [XCUIElement.ElementType.staticText, .button, .textField, .navigationBar, .cell, .switch, .other] {
            labels += app.descendants(matching: t).allElementsBoundByIndex.prefix(250).compactMap { e in
                let s = e.label.trimmingCharacters(in: .whitespaces); return s.isEmpty ? nil : s
            }
            labels += app.descendants(matching: .textField).allElementsBoundByIndex.prefix(30).compactMap { $0.placeholderValue }
        }
        var seen = Set<String>()
        let uniq = labels.filter { seen.insert($0).inserted }
        let a = XCTAttachment(string: uniq.joined(separator: "\n")); a.name = "screen_" + n; a.lifetime = .keepAlways; add(a)
    }
    func back() { if app.navigationBars.buttons.count > 0 { app.navigationBars.buttons.element(boundBy: 0).tap() } }
    func more(_ label: String, _ name: String) {
        app.tabBars.buttons["More"].tap()
        if app.navigationBars["Settings"].exists { back() }
        var n = 0; while !app.staticTexts[label].isHittable && n < 5 { app.swipeUp(); n += 1 }
        guard app.staticTexts[label].exists else { dump("MISSING_" + name); return }
        app.staticTexts[label].tap(); dump(name)
    }

    func testAuditEnglish() {
        app = XCUIApplication()
        app.launchEnvironment["STOCKIT_RESET_LANGUAGE"] = "en"
        app.launchEnvironment["STOCKIT_TEST_PHOTO"] = "/tmp/scan_mouse.jpg"
        app.launch()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Autoriser", "Allow"] where sb.buttons[l].waitForExistence(timeout: 2) { sb.buttons[l].tap() }
        dump("login")
        let user = app.textFields.element(boundBy: 0)
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        user.tap(); user.typeText("admin")
        let pwd = app.secureTextFields.element(boundBy: 0); pwd.tap(); pwd.typeText("admin123")
        app.buttons["Standard login"].tap()
        XCTAssertTrue(app.navigationBars["Dashboard"].waitForExistence(timeout: 15))
        dump("dashboard")

        app.tabBars.buttons["Stock"].tap(); dump("stock")
        app.cells.element(boundBy: 0).staticTexts.element(boundBy: 1).tap(); dump("product_detail")
        if app.buttons["✏️ Edit"].exists { app.buttons["✏️ Edit"].tap(); dump("product_edit"); app.buttons["Cancel"].firstMatch.tap(); sleep(1) }
        app.buttons["Close"].firstMatch.tap()
        app.navigationBars["Inventory"].buttons.element(boundBy: 0).tap(); dump("product_new"); app.buttons["Cancel"].firstMatch.tap()

        app.tabBars.buttons["Tickets"].tap(); sleep(4); dump("tickets")

        app.tabBars.buttons["Scan"].tap(); dump("scan_menu")
        app.buttons["🧠 Scan equipment (AI)"].tap(); sleep(2); dump("scan_asset_camera")
        if app.buttons["testPhoto"].waitForExistence(timeout: 5) {
            app.buttons["testPhoto"].tap()
            if app.navigationBars["Confirm equipment"].waitForExistence(timeout: 90) { dump("scan_confirm") }
            app.buttons["Confirm"].firstMatch.tap(); sleep(2); dump("scan_ask_invoice")
            if app.buttons["📷 Scan the invoice"].exists {
                app.buttons["📷 Scan the invoice"].tap(); sleep(2); dump("scan_invoice_camera")
                app.buttons["Skip"].firstMatch.tap(); sleep(2); dump("scan_ask_label")
                app.buttons["📷 Scan the label"].tap(); sleep(2); dump("scan_label_camera")
                app.buttons["Skip"].firstMatch.tap(); sleep(4); dump("scan_saved")
                if app.alerts.buttons["OK"].exists { app.alerts.buttons["OK"].tap() }
            }
        }
        if app.buttons["Close"].exists { app.buttons["Close"].firstMatch.tap() }
        sleep(1)
        app.tabBars.buttons["Scan"].tap()
        app.buttons["📤 Equipment check-out"].tap(); sleep(2); dump("scanout_camera")
        if app.buttons["testPhoto"].waitForExistence(timeout: 5) {
            app.buttons["testPhoto"].tap()
            if app.navigationBars["Confirm equipment"].waitForExistence(timeout: 90) { dump("scanout_confirm") }
            app.buttons["Confirm"].firstMatch.tap(); sleep(2); dump("scanout_qty")
            let q = app.textFields.element(boundBy: 0); if q.exists { q.tap(); q.typeText("1"); app.buttons["Continue"].tap(); sleep(2); dump("scanout_method") }
            app.buttons["Cancel"].firstMatch.tap()
        } else { app.buttons["Cancel"].firstMatch.tap() }
        sleep(1)
        app.tabBars.buttons["Scan"].tap()
        app.buttons["📦 Package receiving (invoice)"].tap(); sleep(2); dump("receive_camera"); app.buttons["Cancel"].firstMatch.tap(); sleep(1)
        app.tabBars.buttons["Scan"].tap()
        app.buttons["🔢 Batch barcode scan"].tap(); sleep(2); dump("batch"); app.buttons["Cancel"].firstMatch.tap(); sleep(1)

        app.tabBars.buttons["More"].tap(); dump("more")
        app.cells.element(boundBy: 0).tap(); dump("profile"); back()
        more("Categories", "categories"); back()
        more("Suppliers", "suppliers"); app.staticTexts.element(boundBy: 2).tap(); dump("supplier_form"); app.buttons["Cancel"].firstMatch.tap(); back()
        more("Orders", "orders"); back()
        more("Shipments", "shipments"); back()
        more("Reports", "reports"); back()
        more("AI Optimization", "insights"); back()
        more("Demand forecast", "forecast"); sleep(8); dump("forecast_loaded"); back()
        more("AI quality", "ai_quality"); back()
        more("Leaderboard", "leaderboard"); back()
        more("Audit log", "audit"); back()
        more("Users", "users"); back()
        more("Claims", "claims")
        app.navigationBars.buttons.element(boundBy: 1).tap(); dump("claim_new"); app.buttons["Cancel"].firstMatch.tap(); back()
        more("Monthly AI report (PDF)", "pdf"); app.buttons["Generate the monthly report"].tap(); sleep(20); dump("pdf_done"); back()
        more("Vista AI Assistant", "chat"); back()
        more("Settings", "settings")
    }
}

package com.example.stockit.shared

import com.example.stockit.shared.ai.DeliveryNoteParser
import com.example.stockit.shared.ai.PackageLabelParser
import com.example.stockit.shared.ai.TicketMatcher
import com.example.stockit.shared.model.JiraTicket
import com.example.stockit.shared.model.KitRules
import com.example.stockit.shared.network.AIGateway
import com.example.stockit.shared.network.Http
import com.example.stockit.shared.network.JiraClient
import com.example.stockit.shared.network.extractOpenAiContent
import com.example.stockit.shared.session.RoleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class SharedLogicTest {

    @Test
    fun ticketMatcherCapsQuantities() {
        val llm = """```json
            [{"ticketId":"SD-1","qty":3,"reason":"a"},{"ticketId":"SD-2","qty":5,"reason":"b"},{"ticketId":"SD-3","qty":1}]
            ```"""
        val r = TicketMatcher.parseAssignments(llm, 4)
        assertEquals(2, r.size)
        assertEquals(3, r[0].qty)
        assertEquals("SD-2", r[1].ticketId)
        assertEquals(1, r[1].qty)
    }

    @Test
    fun ticketPriorityWeight() {
        assertEquals(5, JiraTicket("A", priority = "Highest").priorityWeight())
        assertEquals(4, JiraTicket("A", priority = "J2: High").priorityWeight())
        assertEquals(1, JiraTicket("A", priority = "J5").priorityWeight())
        assertEquals(0, JiraTicket("A").priorityWeight())
    }

    @Test
    fun adfFlattening() {
        val adf = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"3 souris"}]},
            {"type":"paragraph","content":[{"type":"text","text":"urgent"}]}]}"""
        val issue = """{"key":"SD-9","fields":{"summary":"S","status":{"name":"Open"},"priority":{"name":"High"},
            "duedate":null,"description":$adf,"assignee":{"displayName":"Eya"}}}"""
        val t = JiraClient.parseIssue(Http.json.parseToJsonElement(issue).jsonObject)
        assertEquals("SD-9", t.key)
        assertEquals("Open", t.status)
        assertEquals(null, t.dueDate)
        assertEquals("Eya", t.assignee)
        assertTrue(t.description!!.contains("3 souris"))
        assertTrue(t.description!!.contains("urgent"))
    }

    @Test
    fun openAiContentExtraction() {
        val p = """{"choices":[{"message":{"role":"assistant","content":"Souris \"MX\"\nok"}}]}"""
        assertEquals("Souris \"MX\"\nok", extractOpenAiContent(p))
    }

    @Test
    fun visionHelpers() {
        assertEquals("Clavier", AIGateway.mapToFrench("computer keyboard, keypad"))
        assertEquals("Écran", AIGateway.mapToFrench("monitor"))
        assertEquals("Souris Logitech", AIGateway.clean("\"Souris Logitech MX.\""))
    }

    @Test
    fun poHelpers() {
        assertEquals(1234L, DeliveryNoteParser.extractIntFromPoNumber("PO-1234"))
        assertEquals(null, DeliveryNoteParser.extractIntFromPoNumber("none"))
        assertFalse(PackageLabelParser.poMismatch("PO-3480", "3480"))
        assertTrue(PackageLabelParser.poMismatch("PO-3480", "9999"))
    }

    @Test
    fun kitAndRoles() {
        assertTrue(KitRules.requiresKit("Écran Dell 24"))
        assertFalse(KitRules.requiresKit("Souris"))
        assertEquals("ADMIN", RoleResolver.resolve(mapOf("roles" to "stockit-admin,users")))
        assertEquals("USER", RoleResolver.resolve(emptyMap()))
    }

    @Test
    fun money() {
        assertEquals("1234.50", formatMoney(1234.5))
        assertEquals("0.07", formatMoney(0.07))
    }
}

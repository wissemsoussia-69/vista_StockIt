package com.example.stockit.shared

import com.example.stockit.shared.network.AIGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisionParseTest {
    @Test fun fullJson() = assertEquals("Souris Logitech MX Master 3S",
        AIGateway.parseVisionName("""{"type":"Souris","brand":"Logitech","model":"MX Master 3S","name":"Souris Logitech MX Master 3S"}"""))
    @Test fun fencedNoName() = assertEquals("Écran Dell",
        AIGateway.parseVisionName("```json\n{\"type\":\"Écran\",\"brand\":\"Dell\",\"model\":null}\n```"))
    @Test fun textAround() = assertEquals("Casque Jabra Evolve2 65",
        AIGateway.parseVisionName("Voici : {\"name\": \"Casque Jabra Evolve2 65\"} ."))
    @Test fun nothing() = assertNull(AIGateway.parseVisionName("""{"type":null,"brand":null,"model":null,"name":null}"""))
    @Test fun plainText() = assertEquals("Clavier Apple", AIGateway.parseVisionName("Clavier Apple"))
}

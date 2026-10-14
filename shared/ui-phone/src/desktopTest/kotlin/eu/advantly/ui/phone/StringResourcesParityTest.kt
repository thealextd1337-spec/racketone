package eu.advantly.ui.phone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stellt sicher, dass jeder UI-String auf Englisch (Default, `values/`) UND Deutsch (`values-de/`) existiert.
 * Läuft nur auf der JVM, weil die XML-Dateien direkt gelesen werden (Arbeitsverzeichnis = Modulordner).
 */
class StringResourcesParityTest {
    private val resourcesDir = File("src/commonMain/composeResources")

    private fun stringKeys(qualifier: String): Set<String> {
        val file = File(resourcesDir, "$qualifier/strings.xml")
        assertTrue(file.isFile, "Fehlt: ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map {
                nodes
                    .item(it)
                    .attributes
                    .getNamedItem("name")
                    .nodeValue
            }.toSet()
    }

    @Test
    fun germanAndEnglishHaveTheSameKeys() {
        val english = stringKeys("values")
        val german = stringKeys("values-de")
        assertTrue(english.isNotEmpty())
        assertEquals(english, german, "values/ und values-de/ müssen dieselben Schlüssel haben")
    }
}

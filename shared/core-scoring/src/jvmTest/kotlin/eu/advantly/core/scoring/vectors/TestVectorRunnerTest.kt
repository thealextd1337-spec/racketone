package eu.advantly.core.scoring.vectors

import eu.advantly.core.model.MatchFormat
import eu.advantly.core.scoring.MatchFormatValidator
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Testvektor-Runner (docs/testvectors/README.md §1.3): faltet jeden Vektor bis zu jedem Checkpoint und vergleicht den
 * kanonischen Zustand nach den normativen Vergleichsregeln.
 */
class TestVectorRunnerTest {
    private fun vectorFiles(dir: File): List<File> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()

    private fun runAll(files: List<File>): Pair<Int, List<CheckpointFailure>> {
        var checkpoints = 0
        val failures = mutableListOf<CheckpointFailure>()
        for (file in files) {
            val vector = VectorIo.read(file)
            checkpoints += vector.checkpoints.size
            failures += VectorIo.verify(vector)
        }
        return checkpoints to failures
    }

    @Test
    fun specVectorsAllCheckpointsPass() {
        val files = vectorFiles(RepoPaths.specVectorsDir())
        val (checkpoints, failures) = runAll(files)
        val passed = checkpoints - failures.size
        println("Spezifikations-Testvektoren: ${files.size} Vektoren, $passed/$checkpoints Checkpoints grün")
        if (failures.isNotEmpty()) fail("${failures.size} Checkpoints weichen ab:\n" + failures.joinToString("\n"))
        assertEquals(22, files.size, "Anzahl Spezifikations-Vektoren")
        assertEquals(223, checkpoints, "Anzahl Checkpoints laut docs/testvectors/README.md")
    }

    @Test
    fun goldenVectorsAllCheckpointsPass() {
        val files = vectorFiles(RepoPaths.goldenVectorsDir())
        val (checkpoints, failures) = runAll(files)
        println("Golden-Testvektoren: ${files.size} Vektoren, ${checkpoints - failures.size}/$checkpoints Checkpoints grün")
        if (failures.isNotEmpty()) fail("${failures.size} Checkpoints weichen ab:\n" + failures.joinToString("\n"))
    }

    @Test
    fun specVectorConfigsAreValidAndRoundTrip() {
        for (file in vectorFiles(RepoPaths.specVectorsDir())) {
            val raw = vectorJson.parseToJsonElement(file.readText()).jsonObject.getValue("config")
            val format = vectorJson.decodeFromJsonElement(MatchFormat.serializer(), raw)
            val validation = MatchFormatValidator.validate(format)
            assertTrue(validation.isValid, "${file.name}: ${validation.errors}")
            val reencoded = vectorJson.encodeToJsonElement(MatchFormat.serializer(), format)
            assertEquals(raw, reencoded, "${file.name}: MatchFormat-JSON muss verlustfrei zurückgeschrieben werden")
        }
    }
}

package eu.advantly.gate.check

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

private const val USAGE = """P1-Gate-Werkzeuge (docs/release/p1-gate-protokoll.md)

  compare --db <label>=<advantly.db> [--db <label>=<advantly.db> …] --out <verzeichnis>
          [--since <JJJJ-MM-TT>] [--match <id-präfix>] [--strict]
      Vergleicht die Datenbanken (erste = Referenz, das Handy). Schreibt index.md + je Match eine Datei.
      --strict: Exit-Code 1 bei NICHT BESTANDEN, 2 bei OFFEN.

  battery --log <fieldtest.jsonl> [--log …] --out <verzeichnis> [--label <uhr>] [--max-rate 15]
      Wertet das Feldtest-Log der Uhr aus (%/h je Match). Schreibt akku-<label>.md.
"""

fun main(args: Array<String>) {
    val code =
        try {
            run(args.toList())
        } catch (e: IllegalArgumentException) {
            System.err.println("Fehler: ${e.message}\n\n$USAGE")
            64
        }
    exitProcess(code)
}

/** Einstieg ohne `exitProcess` (testbar); liefert den Exit-Code. */
fun run(args: List<String>): Int {
    val command = args.firstOrNull() ?: throw IllegalArgumentException("Befehl fehlt")
    val options = Options.parse(args.drop(1))
    return when (command) {
        "compare" -> {
            compare(options)
        }

        "battery" -> {
            battery(options)
        }

        "help", "--help", "-h" -> {
            println(USAGE)
            0
        }

        else -> {
            throw IllegalArgumentException("Unbekannter Befehl: $command")
        }
    }
}

private fun compare(options: Options): Int {
    val dbs =
        options.all("db").map { spec ->
            val label = spec.substringBefore('=', "")
            val path = spec.substringAfter('=', "")
            require(label.isNotBlank() && path.isNotBlank()) { "--db erwartet <label>=<pfad>, nicht '$spec'" }
            label to File(path)
        }
    require(dbs.isNotEmpty()) { "mindestens eine --db angeben (erste = Handy/Referenz)" }
    require(dbs.map { it.first }.toSet().size == dbs.size) { "Labels müssen eindeutig sein" }
    val out = File(options.one("out") ?: throw IllegalArgumentException("--out fehlt"))
    val since =
        options.one("since")?.let {
            LocalDate
                .parse(it)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    val prefix = options.one("match")

    val snapshots = dbs.map { (label, file) -> DeviceDatabase.load(label, file) }
    val results =
        IntegrityCheck.check(snapshots) { id, created ->
            (prefix == null || id.startsWith(prefix) || id.endsWith(prefix)) &&
                (since == null || (created != null && created >= since))
        }
    out.mkdirs()
    val now = System.currentTimeMillis()
    File(out, "index.md").writeText(IntegrityReport.index(results, dbs.map { it.first to it.second.absolutePath }, now))
    results.forEach { File(out, IntegrityReport.fileName(it)).writeText(IntegrityReport.match(it)) }

    val overall = IntegrityCheck.overall(results)
    results.forEach {
        println("${it.verdict.label.padEnd(16)} ${IntegrityCheck.short(it.matchId)}  ${it.sport ?: "—"}  ${it.union?.score ?: "—"}")
    }
    println("GESAMT: ${overall.label} (${results.size} Matches) → ${File(out, "index.md").absolutePath}")
    if (!options.flag("strict")) return 0
    return when (overall) {
        Verdict.FAILED -> 1
        Verdict.OPEN -> 2
        else -> 0
    }
}

private fun battery(options: Options): Int {
    val logs = options.all("log").map(::File)
    require(logs.isNotEmpty()) { "mindestens ein --log angeben" }
    logs.forEach { require(it.isFile) { "Log nicht gefunden: ${it.absolutePath}" } }
    val out = File(options.one("out") ?: throw IllegalArgumentException("--out fehlt"))
    val label = options.one("label") ?: "uhr"
    val maxRate = options.one("max-rate")?.toDoubleOrNull() ?: BatteryAnalysis.MAX_RATE_PER_HOUR

    val log = FieldLogParser.parse(logs)
    val matches = BatteryAnalysis.perMatch(log, maxRate)
    val whole = BatteryAnalysis.whole(log, maxRate)
    out.mkdirs()
    val file = File(out, "akku-$label.md")
    file.writeText(BatteryReport.render(label, logs.map { it.absolutePath }, log, matches, whole, System.currentTimeMillis()))
    matches.forEach {
        println(
            "${it.verdict.label.padEnd(16)} ${it.title}  ${it.sport ?: "—"}  ${it.ratePerHour?.let { r ->
                "%.1f %%/h".format(r)
            } ?: "—"}",
        )
    }
    println("Bericht: ${file.absolutePath}")
    return 0
}

/** Minimaler Parser für `--key value`, `--key=value` und Schalter. */
class Options private constructor(
    private val values: Map<String, List<String>>,
) {
    fun all(key: String): List<String> = values[key].orEmpty()

    fun one(key: String): String? = values[key]?.lastOrNull()

    fun flag(key: String): Boolean = key in values

    companion object {
        private val SWITCHES = setOf("strict")

        fun parse(args: List<String>): Options {
            val map = LinkedHashMap<String, MutableList<String>>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                require(arg.startsWith("--")) { "Unerwartetes Argument: $arg" }
                val key = arg.removePrefix("--").substringBefore('=')
                val list = map.getOrPut(key) { mutableListOf() }
                when {
                    '=' in arg.removePrefix("--") && key !in SWITCHES -> {
                        list += arg.substringAfter('=')
                    }

                    key in SWITCHES -> {
                        Unit
                    }

                    else -> {
                        require(i + 1 < args.size) { "Wert für --$key fehlt" }
                        list += args[++i]
                    }
                }
                i++
            }
            return Options(map)
        }
    }
}

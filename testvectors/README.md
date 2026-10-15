# Golden-Testvektoren (generiert)

Hier legt die Kotlin-Engine die Golden-Testvektoren ab (Format siehe [docs/testvectors/README.md](../docs/testvectors/README.md)
und [01 §8.1](../docs/01-architektur.md)). **Nicht von Hand ändern.**

```
gradlew.bat :shared:core-scoring:generateTestVectors     # neu erzeugen (schreibt testvectors/)
gradlew.bat :shared:core-scoring:checkTestVectorDrift    # Drift-Prüfung, hängt an `check`
```

| Ordner | Inhalt |
|---|---|
| `<sport>/presets/` | je Preset aus 02 §4 **und** Disziplin ein kanonischer Beispiel-Vektor (fester Seed, UI-Undos, OPEN-Antworten) |
| `<sport>/rules/` | Export der Regel-Tabellen-Szenarien (`RuleScenarios` in `core-scoring/jvmTest`) |
| `<sport>/random/` | deterministische Zufallsmatches (fester Seed) mit 1–3 Geräten: verzweigte Historien, gleiche Lamports, Doppel-Taps, Widersprüche, Undo-Ketten, Korrekturen, ungültige Events |

Checkpoints enthalten jeweils den **vollständigen** kanonischen Zustand (02 §8).

**Erweiterung gegenüber `schema: 1`:** Events dürfen optional die Hüllenfelder `lamport`, `deviceId`, `parentId` und
`wallClockMs` tragen (in `random/` und in Mehrgeräte-Szenarien unter `rules/`). Sind sie vorhanden, verwendet der
Runner sie statt der synthetischen Hülle; das `events`-Array steht trotzdem in kanonischer Ordnung `(lamport, deviceId, id)`.
Fehlen sie, gilt die synthetische Hülle aus docs/testvectors/README.md §1.1.

Die handgeschriebenen Spezifikations-Vektoren unter [`docs/testvectors/`](../docs/testvectors/README.md) bleiben dort.

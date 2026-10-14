# Golden-Testvektoren (generiert)

Hier legt die Kotlin-Engine ab Welle 1 die Golden-Testvektoren ab: `testvectors/<sport>/<name>.json` (Format siehe
[01 §8.1](../docs/01-architektur.md)). Erzeugen: `gradlew.bat :shared:core-scoring:generateTestVectors`,
Drift prüfen: `gradlew.bat :shared:core-scoring:checkTestVectorDrift` (beides noch Platzhalter).

Die handgeschriebenen Spezifikations-Vektoren unter [`docs/testvectors/`](../docs/testvectors/README.md) bleiben dort
(Regel-Referenz aus 02) und werden nicht hierher verschoben.

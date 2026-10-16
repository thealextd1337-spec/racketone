# P3 – Fitness-Tracker und Fitbit ins Match einbinden

Stand: 16.10.2026. Nutzerauftrag: Auch Tracker ohne Display sollen ihre Fitnessdaten der Handy-App
bereitstellen können; diese Daten werden anschließend dem gespielten Match zugeordnet. Dies erweitert
den vorhandenen Health-Import aus [05 §4](05-watches-health.md). Geplant für P3, noch nicht implementiert.

**Geräteumfang laut Nutzer:** Fitbit Air als erste Referenz; gesamte Fitbit-Wearable-Serie der letzten
fünf Jahre als Zielumfang (Planungsstand 2026: ca. 2021–2026, einschließlich in diesem Zeitraum vertriebener
Generationen). Das schließt Modelle mit Display ein. Vollständige Modellliste vor Umsetzung anhand der
Herstellerunterlagen erfassen; kein Modell allein wegen fehlender Advantly-App ausschließen. Vorhandene
Alters-/Kontogrenzen werden durch diesen Geräteumfang nicht erweitert.

## Verhalten in der App

1. Eigene Datenquelle verbinden und die benötigten Leserechte sowie die bestehende Health-Einwilligung erteilen.
   Eine Advantly-App auf dem Tracker oder eine Zähler-Rolle ist nicht erforderlich.
2. Nach Matchende oder beim Öffnen der Auswertung passende Aufzeichnungen anhand Teilnehmer und Zeitraum
   vorschlagen. Der Nutzer bestätigt die Zuordnung; bei mehreren Treffern wählt er Quelle und Zeitraum.
   Nur dem eigenen Teilnehmer zuordnen, nicht einem fremden Spieler, Director oder Visitor.
3. Verfügbare Puls-, Schritt-, Energie- und Zeitdaten in derselben Match-Auswertung wie Smartwatch-Daten
   anzeigen. Quelle, erfasster Zeitraum, Importstatus und Lücken bleiben sichtbar. Nicht verfügbare Werte
   bleiben unbekannt; es werden weder Nullwerte noch künstliche Messungen eingesetzt.
4. Später synchronisierte Daten erneut einlesen und die bestätigte Zuordnung aktualisieren. Ein erneuter
   Import erzeugt keine zweite Session und verändert weder Punkte noch Ergebnis des Matches.
5. Zuordnung korrigieren oder lösen können. Fremde Originaldaten im Health-Speicher bleiben unverändert.
   Cloud-Sync und Teilen folgen den bestehenden getrennten Einstellungen; standardmäßig nur für mich.

## Schnittstellen und Quellen

- Android: bevorzugt Health Connect. iOS: bevorzugt Apple Health/HealthKit. Voraussetzung ist jeweils,
  dass die Hersteller-App die benötigten Datentypen tatsächlich dorthin schreibt und Leserechte bestehen.
- Fitbit Air zuerst auf Android und iPhone nachweisen. Danach die gesamte Zielserie in einer Matrix aus Modell,
  Handy-Plattform, Hersteller-App, Messwerten, Zeitauflösung und Synchronisationsverzug führen. Status pro
  Kombination: geplant, nachgewiesen oder durch konkrete Schnittstellen-/Kontogrenze eingeschränkt.
  Fehlende Zugänge bleiben offene Arbeit, keine stillschweigende Verkleinerung des Zielumfangs.
- Wenn eine Plattformbrücke fehlt, eine offizielle Hersteller-API auf Zugriff, Kosten und verfügbare Daten
  prüfen; keinen universellen Bluetooth-Import annehmen. Live-Puls ist optional und kein Ersatz für den
  hier beauftragten nachträglichen Import.
- Ein Workout ist hilfreich, aber nicht zwingend: Zeitgestützte Samples können mit bestätigtem Matchfenster
  zugeordnet werden. Reine Tages- oder Workout-Gesamtsummen ohne passende zeitliche Auflösung dürfen nicht
  als exakte Match-/Satzwerte ausgegeben werden. Aktive und gesamte Kalorien unterscheiden.

## Zuordnung und Doppelaufzeichnungen

- UTC-Zeitstempel und ursprüngliche Zeitzone erhalten. Matchbeginn, Ende und Pausen berücksichtigen;
  bei offenem/vergessenem Matchende erst den tatsächlichen Zeitraum bestätigen lassen. Das beendet das Match nicht.
- Ein Workout über mehrere Matches nur anhand zeitlich auflösbarer Daten aufteilen. Derselbe Messabschnitt
  darf nicht mehrfach zu Summen beitragen; uneindeutige oder überlappende Matches erfordern eine Auswahl.
- Originalquelle, externe Datensatz-ID/Version und zugeordneten Zeitraum erhalten. Änderungen und Löschungen
  der Quelle nachvollziehen, wiederholte Synchronisierung idempotent anwenden; keine Import-/Export-Schleifen.
- Bei gleichzeitig getragener Uhr und Tracker pro Metrik/Zeitabschnitt eine nachvollziehbare Quelle wählen.
  Weder Schritte/Kalorien addieren noch widersprüchliche Pulsserien still mitteln. Ergänzungen nicht überlappender
  Abschnitte sind mit sichtbarer Herkunft möglich. Lücken bleiben Lücken, auch beim späteren 5-s-Resampling.
- Kontowechsel, Widerruf, Entknüpfung des Teilnehmers und Löschung berücksichtigen; keine Weiterverarbeitung
  unter dem falschen Konto und kein Wiederimport ausdrücklich entfernter Zuordnungen ohne neue Auswahl.

## Abnahme in P3

Fitbit Air als echter Tracker ohne Display über jeden als unterstützt ausgewiesenen Handy-Pfad: Match spielen,
Hersteller-Sync verzögern, Daten zuordnen und nachliefern. Wiederholter Import bleibt identisch. Zusätzlich:
Tracker plus Smartwatch gleichzeitig, mehrere Matches in einem Workout, fehlende Samples/fehlendes Workout,
unklares Matchende, Zeitzonenwechsel, Quellenkorrektur/-löschung, Kontowechsel und verweigerte Leserechte.
Fehlende Geräte-/API-Zugänge als offene Nachweise führen, nicht durch Simulatorergebnisse als erfüllt markieren.
Zusätzlich die Modellmatrix der letzten fünf Jahre abarbeiten; gemeinsame Importverträge automatisiert prüfen
und die Datenlieferung jeder Modell-/Plattform-Kombination belegen. Air allein belegt nicht die gesamte Serie.

## Geprüfte Grundlagen

Google beschreibt Fitbit Air ausdrücklich als Tracker ohne Display:
[Hersteller-Vorstellung](https://blog.google/products-and-platforms/devices/fitbit/fitbit-air/).

Google dokumentiert den Datenaustausch zwischen Google Health und Health Connect einschließlich getrennter
Lese-/Schreibrechte und einer Datentyp-Matrix. Das belegt den Plattformpfad, nicht die Qualität oder Auflösung
jedes einzelnen Trackers: [Google-Hilfe](https://support.google.com/googlehealth/answer/14506680?hl=en).
Apple verlangt typspezifische HealthKit-Freigaben; verweigerter Lesezugriff kann wie ein leerer Datenbestand
erscheinen: [Apple-Dokumentation](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data).

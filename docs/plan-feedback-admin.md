# Feedback, Featurewünsche, Fehler und Online-Admintool

Stand: 16.10.2026. Nutzerauftrag, noch nicht implementiert. Teil von P2/Betrieb; erste nutzbare Lieferung
vor dem nächsten Online-Pilot. Android und iOS erhalten denselben Meldeweg, DE/EN und alle geplanten Displaygrößen.

## Handy-App: Feedback & Hilfe

- Einstieg im Profil/in den Einstellungen; auch vom Anmeldebildschirm erreichbar, damit Login-Probleme
  gemeldet werden können. Kategorien: Feedback, Featurewunsch, Fehler.
- Kurzer Titel und Beschreibung; bei Fehlern zusätzlich optional Schritte zum Nachstellen, erwartetes und
  tatsächliches Verhalten. Rückkontakt freiwillig; kein erzwungenes öffentliches GitHub-Konto.
- Optionale Diagnosevorschau: App-Version/Commit, OS, Gerätemodell, Displaygröße/Schriftskalierung und relevante
  Verbindungs-/Fehlercodes. Nutzer kann sie prüfen und abwählen. Keine automatischen Matchinhalte,
  Spielernamen, Health-Daten, Zugangsdaten oder vollständigen Logs anhängen.
- Ausgewählte Screenshots als spätere Erweiterung mit Vorschau, Entfernen und Größenlimit; niemals den
  Bildschirm oder Dateien automatisch aufnehmen. Erste Lieferung benötigt Text und optionale Basisdiagnose.
- Lokalen Entwurf erhalten; ohne Netz verständlich anzeigen, dass noch nichts gesendet wurde. Versand
  ausschließlich nach „Absenden“. Wiederholungen derselben Übertragung erzeugen keine doppelte Meldung.
  Erst bestätigter Serverempfang zeigt Erfolg und eine Vorgangsnummer; Entwürfe bleiben löschbar.
- Meldung ist privat. Eine automatische Veröffentlichung als GitHub-Issue oder in einer öffentlichen
  Wunschliste gehört nicht zum Startumfang. Nutzer sehen nur eigene Vorgänge; Rückmeldungen zunächst
  innerhalb der App für angemeldete Nutzer, ohne zusätzliche E-Mail-Anbieter-Abhängigkeit.

## Web-Admintool für den Betreiber

1. Private Inbox mit Kategorien, Suche und Filtern. Meldung lesen, zuweisen, priorisieren, intern kommentieren,
   verwandte Meldungen verbinden; Status neu/in Prüfung/geplant/behoben/geschlossen. Interne Notizen und
   ausdrücklich an den Nutzer gesendete Antworten sind getrennt. „Behoben“ bei Fehlern mit Versionsbezug.
2. Kontosupport mit begrenzter Suche und minimalen Stammdaten; Gerätesitzungen widerrufen und Konten
   begründet sperren/entsperren. Bestehenden Export-/Löschprozess aus P2-K einschließlich Karenz verwenden.
3. Betriebsansicht: App-/Geräteversionen, letzte technische Verbindung, Sync-Fehler und Dienstzustand.
   Keine Spielergebnisse oder Fitnessmessungen als Standard-Diagnose. Darstellung zeigt Zeitpunkt und
   Herkunft der letzten Meldung; fehlende Telemetrie gilt nicht automatisch als funktionierender Betrieb.
4. Rollen für Support und Administration; serverseitig geprüft. Separate Admin-Anmeldung mit zusätzlichem
   Faktor. Adminrechte ausschließlich durch kontrollierte Betreiber-Provisionierung, nie Selbstzuweisung
   über ein editierbares Profil. Adminzugang für den Betreiber als Teil der Einrichtung testen.
5. Änderungen mit Akteur, Zeitpunkt, Ziel, Aktion und Begründung protokollieren; sensible Inhalte nicht in
   das Auditlog kopieren. Destruktive Aktionen vorher mit konkretem Ziel bestätigen. Kein generischer
   SQL-Editor, keine Benutzer-Imitation und kein unbeschränkter Zugriff auf Health-Rohdaten im Startumfang.

## Umsetzung in abnehmbaren Paketen

1. **Privater Meldeweg + Admin-Inbox:** vorhandenes Backend um Meldungen und getrennte interne/öffentliche
   Antworten ergänzen, private RLS/Autorisierung, idempotenter Empfang, Android/iOS-Formular und Admin-Web-UI.
   Angemeldete Nutzer dürfen eigene Meldungen lesen. Meldungen ohne Login über eng begrenzten, gedrosselten
   Server-Endpunkt; kein anonymer Lesezugriff und keine vermeintlich authentifizierte Kontozuordnung aus Texteingaben.
2. **Kontosupport + Betriebsansicht:** nur benötigte Server-Aktionen mit Rollenprüfung/Audit, P2-K-Prozesse
   integrieren. Service-Schlüssel bleiben auf dem Server, niemals in Handy-App oder Admin-Browser.
3. **Online-Abnahme:** eigene Admin-Adresse, Betreiberzugang, Wiederherstellung des Zugangs und private
   Aufbewahrungs-/Löschregeln für Meldungen dokumentieren; zuerst Staging, danach Online-Pilot. Datenminimierung,
   Löschung/Export und Einreichungsgrenzen in das vorhandene Datenschutz-/Betriebskonzept aufnehmen.

Abnahmetests: jede Kategorie auf Android/iOS einreichen, Login-Fehler ohne Login melden, Netzabbruch und
Retry ohne Doppelung, korrekt sichtbare Vorgangsnummer, fremde Meldungen/Anhänge unzugänglich, normale
App-Konten ohne Adminrechte, interner Kommentar nie beim Nutzer, explizite Antwort korrekt zugestellt,
Sperren/Sitzungswiderruf rollenbeschränkt und protokolliert. Kein Versand von Diagnosefeldern ohne Auswahl.

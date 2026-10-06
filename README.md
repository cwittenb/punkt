# .

„.“ (gesprochen „Punkt“) ist eine Übungs-App für einen nondualen Übungsweg: Morgen-Gate, Morgenanker, Hauptpraxis, Journal, Tagesskala, Wochencheck und Begegnung.

Die Oberfläche ist eine Web-App (`app/src/main/assets/web/index.html`). Die Android-Hülle ergänzt, was der Browser nicht kann:

| Funktion | Umsetzung |
|---|---|
| Morgen-Gate | Bedienungshilfe `GateService`. Holt die App nach vorne, bis Anker, Journal und Skala erledigt sind |
| Tagesimpulse, stille Impulse, Stammfenster, Schwellen | Exakte Wecker (`Planer`) und stille Mitteilungen (`Notif`) |
| Handy liegt mit dem Display nach unten | Beschleunigungssensor während der Praxis; das Display wird dann fast dunkel |
| Diktat ins Journal | `Diktat`: Spracherkennung von Android, nur auf dem Gerät (`createOnDeviceSpeechRecognizer`). Der Text landet live im Feld, es entsteht keine Audiodatei |
| Sprachnotizen (Rückfall) | `Recorder`, wenn keine Erkennung auf dem Gerät da ist oder das deutsche Sprachpaket fehlt. Dateien bleiben im privaten App-Ordner |
| Sprachbegleitung | Android-Sprachausgabe (Deutsch) |
| NFC-Aufkleber | Adresse `punkt://anker` startet den Morgenanker |
| Export | Downloads/Punkt |

## Datenschutz

- Die App hat **keine Internet-Berechtigung**. Sie kann nichts senden.
- Das Diktat nutzt ausschließlich die Erkennung auf dem Gerät. Sie läuft im Sprachdienst von Android (meist Google), das Audio verlässt das Handy dabei nicht. Fehlt das deutsche Sprachpaket, stößt die App den Download beim Sprachdienst an und nimmt bis dahin eine Sprachnotiz auf.
- Keine Cloud-Sicherung und keine Übertragung beim Handywechsel (`allowBackup=false`, `datenregeln.xml`). Zum Umziehen: in der App exportieren und auf dem neuen Handy importieren.
- In diesem Repository liegt nur Code, keine Journaldaten.

## Installation auf dem Handy (Samsung S20, Android 13)

1. Auf dem Handy die neueste Version unter **Releases** öffnen und `punkt-0.x.apk` laden.
2. Die Datei öffnen. Beim ersten Mal fragt Android, ob der Browser Apps installieren darf: erlauben.
3. Die App öffnen und Mitteilungen erlauben.
4. **Morgen-Gate einschalten.** Android 13 sperrt Bedienungshilfen bei selbst installierten Apps, deshalb zuerst:
   - Einstellungen → Apps → „.“ → ⋮ (oben rechts) → **Eingeschränkte Einstellungen zulassen**
   - Dann Einstellungen → Eingabehilfe → Installierte Apps → **. Morgen-Gate** → Ein
   - Oder direkt in der App: Optionen → Morgen-Gate → „Bedienungshilfen öffnen“
5. **Akku**, damit Samsung die Wecker nicht verschluckt:
   - Einstellungen → Apps → „.“ → Akku → **Uneingeschränkt**
   - Einstellungen → Akku und Gerätewartung → Akku → Hintergrundnutzungslimits: „.“ unter **Nie schlafende Apps** eintragen.
6. Optional **NFC-Aufkleber**: Mit der App „NFC Tools“ einen Datensatz vom Typ *Benutzerdefinierte URL/URI* mit `punkt://anker` schreiben. Aufkleber dorthin, wo der Morgenanker stattfindet.

Updates: neue APK aus Releases laden und darüber installieren. Die Daten bleiben erhalten.

## Was das Gate frei lässt

Telefon, Kontakte, Uhr, Notruf, Einstellungen, Startbildschirm, Tastatur und Smart Life. Weitere Apps lassen sich in den Optionen unter „Im Gate erlaubt“ hinzufügen. Wird die Bedienungshilfe abgeschaltet, fragt die App beim nächsten Öffnen nach einem Satz dazu.

## Zeiten

- Tagesimpulse: verteilt zwischen 8 Uhr (frühestens drei Stunden nach der Morgenstunde) und 21 Uhr
- Stille Impulse: im Fenster aus den Begegnungs-Einstellungen
- Während einer Übung kommen keine Impulse
- Alle Impulse vibrieren als Wecker-Vibration, auch im Lautlos-Modus

## Bauen

Jeder Push auf `main` baut über GitHub Actions eine signierte APK und legt sie als Release ab.

Einmalig nötig: das Secret **PUNKT_SIGNING** (Settings → Secrets and variables → Actions → New repository secret). Damit wird `signing/punkt.jks.enc` entschlüsselt. Ohne dieses Passwort lässt sich keine Version bauen, die sich über die installierte App legt. Also gut aufbewahren.

Aufbau:

```
app/src/main/assets/web/     Web-App und Schriften (OFL)
app/src/main/java/de/punkt/app/
  MainActivity.kt   WebView, Zurück-Taste, Sensor, Sprachausgabe, Systemleisten
  Bruecke.kt        window.PunktNative für die Web-App
  Planer.kt         Termine des Tages, nächster Wecker
  Empfaenger.kt     Wecker und Neustart
  Notif.kt          Kanäle und stille Mitteilungen
  GateService.kt    Morgen-Gate
  Diktat.kt         Diktat ins Textfeld, nur auf dem Gerät
  Recorder.kt       Sprachnotizen (Rückfall)
  Speicher.kt       Plan, Zähler, Zustand, Gate-Status
```

Die Web-App erkennt die Hülle an `window.PunktNative` und läuft ohne sie unverändert im Browser.

Die Web-App liegt einmal in `web/punkt.html`, identisch mit der Web-Version. Die Übungslogik (Schritte, Takt, Pausen, Ansagen) liegt getrennt in `web/engine.js` und wird per `<script src>` geladen. `python3 tools/web.py` baut daraus `app/src/main/assets/web/index.html`: mit Dokumentgerüst, ohne Google Fonts und mit eingebetteter Engine. Die Build-Pipeline macht das vor jedem Build selbst.

### Begleitungs-Engine (`web/engine.js`)

Eine Übung ist eine Folge von Schritten. Jeder Schritt hat einen Treiber (`zeit`, `atem`, `halten`, `frei`), ein Ende (`auto` oder `tap`), Gates (`lage` = Display unten; `sichtbar` gilt immer), optional Ansage, Cues und Musik. Die Engine hält genau eine Uhr (Schrittzeit als Summe der Laufabschnitte), genau einen Zustandsautomaten (`warm → running/paused → stepDone → …`) und eine Sprachwarteschlange (Ansage vor Cue vor Atemwort). Hintergrund und Display-oben sind Pausen, die Uhr steht. Tippen kurz nach einem Schrittwechsel wird verworfen. `planZuFormat()` in `punkt.html` übersetzt die Pläne (`ankerPlan`, `hauptPlan`, `uebungPlan`) in Engine-Schritte; neue Formate sind neue Schrittlisten, keine neue Logik.

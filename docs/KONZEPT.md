# Wetter-App – Konzept und Plan

Stand: 8. November 2026 · Status: Planung abgeschlossen, noch kein App-Code

Eine Android-Wetter-App nur für den eigenen Gebrauch (APK). Aussehen und Bedienung
orientieren sich am Stil der iOS-Wetter-App, sind aber eigenständig umgesetzt und
**immer im Dark Mode**. Schwerpunkt: **heute** und **Deutschland mit Grenzgebieten**.

---

## 1. Rahmen

| Punkt | Entscheidung |
|---|---|
| Nutzer | Nur der Besitzer, APK auf dem eigenen Handy |
| Region | Deutschland und Grenzgebiete. Europa/Ausland kommt später |
| Vorhersage-Zeitraum | Höchstens 10 Tage, Schwerpunkt heute |
| Modus | Immer dunkel |
| Sprache | Deutsch |
| Hintergrundaktivität | **Keine.** Die App arbeitet nur, solange sie geöffnet ist |
| Benachrichtigungen, Widget | Nein |

## 2. Aussehen

- **Stil:** wie die iOS-Wetter-App: großer Kopfbereich, halbtransparente dunkle Kacheln mit
  kleiner grauer Überschrift in Großbuchstaben, weiche Verläufe, ruhige Animationen.
- **Schrift:** Inter (frei, SIL Open Font License). Große Temperatur in sehr dünnem Schnitt.
- **Symbole:** eigene Vektor-Symbole im gleichen Stil (gelbe Sonne, weiße Wolken, blaue Tropfen,
  Mond mit Sternen). Apples Symbole dürfen nicht verwendet werden.
- **Hintergrund (abgenommen):** immer dunkel, Farbton nach Tageszeit, eingefärbt nach Wetter,
  mit Animation. Vorlage: [`docs/hintergrund-vorschau.html`](hintergrund-vorschau.html)

  | Tageszeit | Grundton | Effekt |
  |---|---|---|
  | Sonnenaufgang | dunkles Violettblau | orange-violettes Glühen unten |
  | Tag | tiefes Dunkelblau (kein Hellblau) | bei Sonne warmer Schein oben rechts |
  | Sonnenuntergang | dunkles Violett | orange Glühen am Horizont |
  | Nacht | Nachtblau | Sterne bei klarem Himmel |

  | Wetter | Effekt |
  |---|---|
  | Klar | nur Tageszeit-Effekt |
  | Teils bewölkt | prozedurale Wolkenfelder oben, Lücken |
  | Bedeckt | geschlossene, strukturierte Wolkendecke |
  | Regen | Wolkendecke, schräge Regenstreifen |
  | Gewitter | sehr dunkel, gelegentliches Blitzleuchten |
  | Schnee | Wolkendecke, fallende Flocken |
  | Nebel | zwei gegenläufige Schwaden-Schichten, unten dichter |

  Wolken und Nebel werden prozedural erzeugt (fBm-Rauschen mit Domain Warping, Licht von oben).
  Einfache Flecken-Wolken sind ausdrücklich unerwünscht.

## 3. Bildschirme

### 3.1 Hauptbildschirm (pro Ort eine Seite, wischen zwischen Orten)

Von oben nach unten:

1. **Kopfbereich:** Pfeil + „Mein Standort“, Ortsname, große Temperatur, Wetterzustand,
   „H: 10° T: 4°“. Schrumpft beim Scrollen zu „6° | Teils bewölkt“.
2. **Unwetterwarnung** (Kachel, nur wenn aktiv, immer ganz oben).
3. **Wetterlage (stündlich, 24 h):** drei Umschalter **Temperatur · Regen · Wind**.
   Sonnenaufgang und -untergang erscheinen als **eigene Einträge** zwischen den Stunden
   („17:35 Sonnenuntergang“).
4. **10-Tage-Liste:** Tag, Symbol, Regenwahrscheinlichkeit, Tiefst-/Höchstwert mit farbigem
   Temperaturbalken (Punkt = jetzt). Wechselt mit den Umschaltern: Regen zeigt mm + %,
   Wind zeigt Windkurve + Spanne. Tippen öffnet die Tagesdetails.
   Hinweis **„unsicher“**, wenn die Wettermodelle deutlich voneinander abweichen.
5. **Kacheln** (Reihenfolge veränderbar, siehe 3.2).
6. **Fußzeile:** Datenquellen.

**Leiste unten:** links Karten-Knopf (Vollbild-Radar), Mitte Seitenpunkte der Orte
(erster Punkt = Standort-Pfeil), rechts Ortsliste.

### 3.2 Kacheln

| Kachel | Inhalt |
|---|---|
| Unwetterwarnung | DWD-Warnung mit Stufe, Zeitraum, Text. Nur sichtbar, wenn aktiv |
| Regenradar | DWD-Radar als Kartenausschnitt. Tippen öffnet das Vollbild-Radar |
| Regen nächste 2 Std. | Diagramm in 15-Minuten-Schritten, „Regen ab 15:20, endet 16:05“ |
| Niederschlag heute | mm heute, Hinweis auf die nächsten Tage („2 mm am Dienstag erwartet“) |
| Windkarte | Karte mit animierten Windströmungen |
| Wind | Wind, Böen, Richtung mit Kompass |
| Gefühlt | gefühlte Temperatur mit kurzer Erklärung |
| Feuchtigkeit | Luftfeuchtigkeit in %, Taupunkt |
| Durchschnittswerte | Abweichung vom langjährigen Mittel („+1° über dem Durchschnitt“) |
| Sichtweite | km mit Einordnung („Dichter Nebel“) |
| Sonnenauf-/untergang | Uhrzeit mit Sonnenbogen |
| Mondphase | Phase, Beleuchtung, nächster Mondaufgang, Tage bis Vollmond |

Nicht gewünscht: Pollen, Luftqualität, UV-Index, Luftdruck (später nachrüstbar).

**Bearbeiten:** lange auf eine Kachel drücken → Bearbeitungsmodus. Kacheln wackeln,
lassen sich per Ziehen verschieben, mit ⊖ ausblenden und über „+ Kachel“ wieder hinzufügen.
Reihenfolge und Auswahl werden gespeichert.

### 3.3 Vollbild-Radar

- Dunkle Karte, Standort-Markierung mit aktueller Temperatur.
- Legende: Leicht · Mäßig · Stark · Extrem.
- Zeitleiste mit Abspielen:
  - **Vergangenheit:** DWD-Radar, letzte 2 Stunden (5-Minuten-Schritte)
  - **Jetzt**
  - **+2 Stunden:** DWD-Radar-Vorhersage (5-Minuten-Schritte)
  - **danach bis +12 Stunden:** Modell-Niederschlag (ICON-D2) im Radar-Look, stündlich
- Ebenen-Knopf: Niederschlag, Wind.

### 3.4 Ortsliste

- Erster Eintrag immer **Mein Standort**.
- Gespeicherte Orte mit aktueller Temperatur und Zustand, verschiebbar, löschbar.
- Suche nach Orten (Open-Meteo Geocoding, Deutsch).

### 3.5 Einstellungen

- **Einheiten:** Temperatur °C/°F, Wind km/h · m/s · Bft, Niederschlag mm.
  Standard: °C, km/h, mm.
- **Drive & Logging:** verbundener Drive-Ordner, Anzahl noch nicht hochgeladener Logs,
  „Ordner neu wählen“.
- **Version / Update:** siehe Abschnitt 6.

## 4. Verhalten

- **Start:** immer zuerst der aktuelle Standort. Andere Orte per Wischen.
- **Standort und Wetterdaten** werden nur bei geöffneter App abgefragt, beim Öffnen
  und per Herunterziehen (Pull-to-refresh).
- **Ohne Internet:** zuletzt geladene Daten anzeigen, mit Zeitstempel „Stand 14:05“.
- **Ohne Standort-Berechtigung:** Hinweis und Ortssuche anbieten.
- Beim Verlassen der App endet jede Aktivität. Es gibt keinen Hintergrunddienst und keine
  periodischen Aufgaben.

## 5. Datenquellen

| Zweck | Quelle | Hinweis |
|---|---|---|
| Vorhersage 0–2 Tage | Open-Meteo, Modell **ICON-D2** (DWD) | 2,2 km Raster, stündlich und 15 min |
| Vorhersage Tag 3–10 | Open-Meteo, Modell **ECMWF IFS** | bestes Mittelfrist-Modell |
| „unsicher“-Hinweis | Vergleich ICON und ECMWF | bei deutlicher Abweichung bei Temperatur oder Regen |
| Regen nächste 2 Std. | Open-Meteo `minutely_15` (ICON-D2), später DWD-Radar am Punkt | |
| Regenradar | **DWD** Radar-Komposit inkl. 2-h-Vorhersage (Open Data / GeoServer) | nur Deutschland und Grenzgebiete. Genaue Ebenen werden beim Bau festgelegt |
| Modell-Regen als Karte | ICON-D2 über Gitterpunkte, als Fläche gezeichnet | für +2 h bis +12 h |
| Unwetterwarnungen | **DWD** Warnungen auf Gemeindeebene | |
| Durchschnittswerte | Open-Meteo Historical/Climate (Mittel 1991–2020) | |
| Ortssuche | Open-Meteo Geocoding | `language=de` |
| Mond | lokal berechnet | keine Abfrage nötig |
| Kartengrundlage | freie dunkle Vektorkarte (z. B. MapLibre + OpenFreeMap) | ohne API-Schlüssel |

Alle Quellen sind kostenlos und ohne Anmeldung. RainViewer wird nicht verwendet, weil der
kostenlose Zugang seit dem 1. Januar 2026 keine Vorhersage mehr liefert.

## 6. Updates (Knopf, nicht automatisch)

- In den Einstellungen gibt es einen Versionsknopf: „Version 0.1.0 · Nach Updates suchen“.
- **Erst beim Tippen** fragt die App die neueste GitHub-Release-Version ab
  (öffentliches Repo `KvnStl924/wetter-app`, kein Token nötig). Im Hintergrund wird nie gesucht.
- Gibt es eine neuere Version, zeigt der Knopf „Update auf 0.2.0 verfügbar · Installieren“.
- Erneutes Tippen lädt die APK herunter und öffnet den Installieren-Dialog von Android.
  Diese eine Bestätigung verlangt Android immer.
- Alle Update-Schritte werden in den Drive-Ordner „Update-Protokoll“ mitgeschrieben.
- Vorbild ist der Update-Ablauf aus StreetLab (`StreetLabUpdateManager`, `StreetLabUpdateInstaller`),
  aber ohne Scheduler und ohne Hintergrund-Worker.

## 7. Logging und Fehlerberichte

**Ablauf:**

1. **App öffnen:** neue Logdatei, das Logging läuft automatisch mit.
2. Protokolliert wird **alles**:
   - App-Start und -Ende, Version
   - jede Berührung mit Ziel („Kachel Regenradar → Vollbild geöffnet“, „Umschalter Regen“, „Wisch → Mechernich“)
   - Standortabfragen (Ort, Genauigkeit, Dauer)
   - jede Datenabfrage (Quelle, Modell, Dauer, Größe, Ergebnis)
   - Fehler, Zeitüberschreitungen, Wiederholungen
3. **App verlassen:** Logging endet, die Datei wird **einmal** hochgeladen nach
   `Apps › Wetter-App › 03 Logs - Fehlerberichte › Logs`.
4. **Upload erfolgreich:** Datei wird sofort vom Handy gelöscht. Danach läuft nichts mehr.
5. **Kein Internet beim Verlassen:** Datei bleibt liegen und wird beim nächsten Öffnen hochgeladen.
   Es wird nicht im Hintergrund gewartet.
6. **Absturz:** Bericht mit Stacktrace geht nach `› Absturzberichte` und wird beim nächsten
   Öffnen hochgeladen.
7. **Platzgrenze:** höchstens 20 MB Logs auf dem Handy. Ältere werden gelöscht.

**Drive-Zugriff:** wie in StreetLab über den Android-Dateiauswahldialog. Beim ersten Start wählt
der Nutzer einmal den Ordner `Apps › Wetter-App`. Die App merkt sich die Berechtigung.
Kein Google-Login nötig.

Beispiel-Zeilen:

```
21:14:03.120  APP     App geöffnet (Version 0.1.0)
21:14:03.450  ORT     Standort ermittelt: Saffig (Genauigkeit 12 m, 330 ms)
21:14:03.910  DATEN   Open-Meteo ICON-D2 geladen (412 ms, 38 kB)
21:14:08.731  TIPP    Wetterlage → Umschalter „Regen“
21:14:19.088  TIPP    Kachel „Regenradar“ → Vollbild-Radar geöffnet
21:14:41.660  FEHLER  DWD-Warnungen: Zeitüberschreitung nach 10 s, neuer Versuch 1/2
21:14:58.300  APP     App verlassen → Upload gestartet
21:14:59.120  DRIVE   Upload ok (log_2026-11-08_21-14-03.txt, 14 kB) → lokal gelöscht
```

## 8. Google Drive

`Meine Ablage › Apps › Wetter-App`

| Ordner | Inhalt |
|---|---|
| 01 Quellcode | Sicherungen des Quellcodes |
| 02 APK - zum Installieren | APK-Dateien |
| 03 Logs - Fehlerberichte | Unterordner Logs, Absturzberichte, Update-Protokoll. Füllt die App selbst |
| 04 Tests - Screenshots | Testbilder |
| 05 Bilder und Gestaltung | u. a. Inspiration (Screenshots der iOS-Wetter-App) |
| 06 App-Idee | dieser Plan |
| 07 Signierschlüssel - privat | Keystore für die Release-Signatur |

## 9. Technik

| Bereich | Wahl |
|---|---|
| Sprache, UI | Kotlin, Jetpack Compose, Material 3 (stark angepasst) |
| Architektur | MVVM, Repository, Kotlin Coroutines/Flow |
| Netzwerk | Ktor oder Retrofit + kotlinx.serialization |
| Speicher | DataStore (Einstellungen, Kachel-Reihenfolge, Orte), Cache als Datei |
| Karte | MapLibre Android |
| Hintergrund-Grafik | Compose Canvas / AGSL-Shader (ab Android 13), Fallback Canvas |
| Standort | Fused Location Provider, nur im Vordergrund |
| Drive | Storage Access Framework (wie StreetLab) |
| Paketname | `de.schee.wetter` |
| Min. Android | 8.0 (API 26) |
| Build | Gradle (Kotlin DSL), GitHub Actions baut die APK, Release bei Tag `v*` |

## 10. Bau-Reihenfolge

1. **Grundgerüst:** Projekt, Theme, Schrift, Hintergrund-Engine, Logging, Drive-Ordner, Update-Knopf.
   Damit ist ab der ersten APK jede Fehlersuche über Drive möglich.
2. **Hauptbildschirm mit echten Daten:** Standort, ICON-D2/ECMWF, Kopfbereich, Wetterlage,
   10-Tage-Liste, Sonnenauf-/untergang in der Stundenliste.
3. **Kacheln:** alle 12, Bearbeitungsmodus per langem Drücken.
4. **Radar und Karten:** DWD-Radar, Vollbild mit Zeitleiste, Modell-Regen, Windkarte.
5. **Orte und Einstellungen:** Ortsliste, Suche, Wischen, Einheiten.
6. **Feinschliff:** Animationen, Offline-Verhalten, Tests, signierte Release-APK.

## 11. Später (nicht in Version 1)

- Europa/Ausland (Radar außerhalb DE, MeteoAlarm-Warnungen)
- Pollen, Luftqualität, UV-Index, Luftdruck als Kacheln
- Weitere Sprachen

## 12. Versionsnummern und Veröffentlichung

- Schema **`vX.Y.Z`**: X = großes Update (neue Funktion, neue Oberfläche), Y = kleines Update,
  Z = Fehlerbehebung. Start bei `v0.1.0`.
- Während des Aufbaus: jede fertige Phase erhöht Y (Phase 1 = `0.1.0` … Phase 6 = `0.6.0`),
  Fehlerbehebungen erhöhen Z. Wenn alles fertig und stabil ist: `1.0.0`.
- Android-`versionCode` = X·10000 + Y·100 + Z.
- Veröffentlichen: Tag `vX.Y.Z` → GitHub Actions baut, signiert und erstellt das Release mit APK,
  SHA-256-Prüfsumme und Änderungstext. Die APK kommt zusätzlich nach Drive
  „02 APK - zum Installieren“ (`Wetter-vX.Y.Z.apk`).
- **Signierschlüssel:** ein fester Schlüssel ab der allerersten APK, als GitHub-Secret und als
  Sicherung in Drive „07 Signierschlüssel - privat“. Ohne denselben Schlüssel lässt Android kein Update zu.
- Update-Knopf: GitHub Releases API (öffentlich, kein Token), Download mit Fortschritt,
  Prüfsumme kontrollieren, Installation über den Android-Installer.

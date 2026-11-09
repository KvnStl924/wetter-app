# Änderungen

Schema `X.Y.Z`: X = großes Update, Y = kleines Update, Z = Fehlerbehebung.
## 0.3.4 – Weniger Abstand zwischen den Kacheln
- Abstand zwischen allen Kacheln halbiert (12 → 6 dp), die Kacheln werden dadurch etwas breiter

## 0.3.3 – Regenradar-Kachel repariert
- Die Regenradar-Kachel war auf dem Handy zu niedrig: Titel und Beschreibung fehlten, die Karte war unten abgeschnitten

## 0.3.2 – Drive-Ordner abgesichert
- Wird in der Ordnerauswahl versehentlich „03 Logs - Fehlerberichte“ statt „Wetter-App“ gewählt, legt die App keinen zweiten 03-Ordner mehr darin an
- Die APK wird nur noch in „Wetter-App › 02 APK“ abgelegt; bei falschem Ordner zeigen die Einstellungen einen Hinweis

## 0.3.1 – Update-Download repariert
- Update-Download läuft jetzt über die GitHub-Schnittstelle; der normale Download-Link von GitHub antwortete für dieses Projekt nur mit Fehler 504
- Bei kurzen GitHub-Störungen versucht die App es automatisch ein zweites Mal
- Einstellungen › Drive & Logging zeigt den letzten Upload-Fehler an

## 0.3.0 – Kacheln
- 12 Kacheln im iOS-Stil: Unwetterwarnung, Regenradar, Regen nächste 2 Std., Wind (Kompass), Windkarte, Gefühlt, Feuchtigkeit, Niederschlag heute, Sichtweite, Durchschnitt, Sonnenauf-/untergang, Mondphase
- Amtliche DWD-Unwetterwarnungen für deinen Ort, ganz oben, nur wenn aktiv (Tippen zeigt Details)
- Regenradar-Kachel mit aktuellem DWD-Radarbild und Kreisgrenzen (Vollbild mit Zeitleiste ab 0.4.0)
- Mondphase, Mondaufgang und nächster Vollmond werden auf dem Handy berechnet
- Durchschnitt: Vergleich mit dem Mittel 1991–2020
- Kacheln bearbeiten: lange auf eine Kachel drücken → verschieben (am Griff ziehen), ausblenden, wieder hinzufügen

## 0.2.0 – Echte Wetterdaten
- Standort (nur bei geöffneter App) und Ortsname; ohne Berechtigung Ortssuche
- Vorhersage aus zwei Modellen: ICON-D2 vom DWD für heute und morgen, ECMWF bis Tag 10
- Hinweis „unsicher“, wenn die Modelle deutlich voneinander abweichen
- Kopfbereich mit Temperatur, Zustand, Höchst-/Tiefstwert; schrumpft beim Scrollen
- Wetterlage stündlich mit Umschaltern Temperatur · Regen · Wind, Sonnenauf- und -untergang als eigene Einträge
- 10-Tage-Liste mit Temperaturbalken; Tippen öffnet die Tagesdetails
- Hintergrund folgt dem echten Wetter und den echten Sonnenzeiten
- Herunterziehen aktualisiert; ohne Netz wird der letzte Stand gezeigt
- Die mittlere Taste unten öffnet die Ortssuche; Hintergrund-Vorschau jetzt in den Einstellungen

## 0.1.1 – Fehlerbehebungen
- Drive: Ordner werden nicht mehr doppelt angelegt (z. B. zweimal „Logs“)
- Leiste unten: Karten-Knopf und Ortspunkte zeigen jetzt einen Hinweis, wann sie freigeschaltet werden

## 0.1.0 – Grundgerüst
- Dunkler, animierter Hintergrund nach Wetter und Tageszeit (zum Ausprobieren auf dem Hauptbildschirm)
- Eigene Wetter-Symbole und Schrift Inter
- Logging: protokolliert jede Berührung, lädt beim Verlassen nach Google Drive hoch und löscht danach lokal
- Absturzberichte und Update-Protokoll auf Drive
- Einstellungen: Einheiten, Drive & Logging, Update-Knopf (sucht nur auf Tippen)
- Die eigene APK wird beim Öffnen in „02 APK - zum Installieren“ abgelegt

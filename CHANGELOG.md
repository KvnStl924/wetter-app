# Änderungen

Schema `X.Y.Z`: X = großes Update, Y = kleines Update, Z = Fehlerbehebung.
## 0.6.7 – Radar wieder flüssig
- Die Auswahl der Orte läuft im Hintergrund statt im Haupt-Thread – kein Ruckeln mehr, wenn die Karte nach dem Wischen stehen bleibt
- Kartendaten werden seltener und nur bei nahem Zoom nach Dörfern abgefragt; der Standort-Takt löst keine Neuberechnung mehr aus

## 0.6.6 – Orte überall, jede Stadt mit Wert
- Eingebaute Ortsliste der Welt (GeoNames, Orte ab 1000 Einwohnern, deutsche Namen): der freie Platz wird immer gefüllt – weit draußen Großstädte, beim Hineinzoomen früh Kleinstädte und Dörfer
- Die Ortsnamen der Karte selbst sind aus: jede gezeigte Stadt hat einen Wert – bei Wind km/h, sonst die Temperatur (auch bei Regen, Wolken, Blitzen)
- Ländernamen in derselben Schrift, dezent, und sie weichen den Werten aus
- Beschriftungen dürfen sich nicht mehr überlappen (es zählt die echte Größe von Name und Wert)

## 0.6.5 – Eigener Ort lesbar, mehr Orte
- Eigener Ort: Name über und Wert unter dem blauen Standortpunkt – vorher verdeckte der Punkt den Wert
- Beim Hineinzoomen kommen Kleinstädte und Dörfer dazu (der Filter hatte sie aussortiert)
- Zahlen in den Pillen heller und besser lesbar

## 0.6.4 – Mehr Städte, kleinere Werte
- Deutlich mehr Städte mit Wert: die App liest die Orte direkt aus den Kartendaten (bis 0.6.3 fehlten sie, sobald Werte gezeigt wurden)
- Jede gezeigte Stadt hat einen Wert; beim Hineinzoomen kommen Kleinstädte und Dörfer dazu
- Wert-Pillen deutlich kleiner, Zahl sauber mittig

## 0.6.3 – Neue Beschriftung im Radar
- Städte wie bei Sky Radar: Name weiß und fett, darunter der Wert in einer Pille mit Rand und Zahl in der passenden Farbe (kalt blau, warm orange)
- Hotspots zeigen nur die Pille; die grauen Ortsnamen der Karte werden dann ausgeblendet, damit nichts doppelt steht

## 0.6.2 – Windkarte: Küsten sichtbar
- Über dem Meer liegt bei Wind ein dunkler Schleier: der Wind bleibt sichtbar, Land und Meer heben sich klar ab

## 0.6.1 – Karte unter Temperatur und Wind lesbar
- Grenzen und Flüsse liegen jetzt über der Farbfläche (Temperatur, Wind, Wolken) – vorher verdeckt
- Temperatur färbt nur das Land, das Meer bleibt dunkelblau (Küsten klar erkennbar); Wind weiterhin auch über dem Meer
- Landesgrenzen etwas heller

## 0.6.0 – Feinschliff
- Ohne Netz: kleiner Hinweis „Offline · Stand 14:05“ statt Fehlermeldung; gezeigt wird der letzte gespeicherte Stand
- Kommt das Netz zurück, aktualisiert die App von selbst (nur solange sie offen ist)
- „Mein Standort“ behält ohne Netz seinen Ortsnamen; ohne GPS-Signal gilt der zuletzt bekannte Ort
- Radar ohne Netz: Temperatur, Wind und Wolken vom zuletzt gespeicherten Stand
- Weiche Animationen: Inhalt blendet beim Laden sanft ein, die große Temperatur zählt zum neuen Wert
- Mehr automatische Tests (Ortsliste, Blitze, Weltdaten, Wettertexte)

## 0.5.4 – Radar: Datumsgrenze, Blitze fest auf der Karte
- Temperatur, Wind und Wolken gehen über die Datumsgrenze (Pazifik) weiter – vorher endete die Fläche mit harter Kante
- Blitze und Werte werden direkt in der Karte gezeichnet und gehen beim Wischen exakt mit (vorher hingen sie hinterher)
- Blitz-Alter stimmt: eine Stelle zählt ab dem ersten Auftauchen (vorher war fast alles „neu“); weit herausgezoomt kleinere Punkte

## 0.5.3 – Radar überarbeitet
- Sofort bereit: Temperatur, Wind, Wolken und Blitze werden beim Öffnen der App für die ganze Welt geladen (und auf dem Handy zwischengespeichert) – beim Wischen und Zoomen wird nichts mehr nachgeladen
- Immer nur eine Ebene: Regen, Wind, Temperatur oder Wolken; Blitze lassen sich zum Regen dazuschalten
- Temperatur und Wind weltweit als feine, weiche Farbfläche (DWD-Modell ICON) – keine Kanten, keine Lücken, auch über dem Pazifik
- Werte an Städten auf der ganzen Welt und an auffälligen Stellen (heiß, kalt, windig) statt im festen Raster
- Blitze als Punkte: neue rot-gelb mit Leuchtring, ältere gelb, dann orange (letzte Stunde)
- Wolken weich und ohne Kanten; kalter Boden (Winter, Polargebiete) wird nicht mehr als Wolke gezeigt
- Schlichtere Karte: nur Land, Wasser, Grenzen und Orte – keine Straßen, Bahnen und Gebäude mehr
- Bedienfeld unten aufgeräumt: eine Legende, kurze Quellenangabe

## 0.5.2 – Wolken und Blitze im Radar
- Neue Ebene „Wolken“: Satellitenbild (EUMETSAT) – über Europa, Afrika und dem Atlantik alle 10 Minuten, sonst weltweit alle 3 Stunden; Wolken weiß, wolkenfreie Gebiete durchsichtig
- Neue Ebene „Blitze“: Blitze der letzten Minuten vom Satelliten MTG (Europa, Afrika, Atlantik), alle 5 Minuten neu
- Beide lassen sich mit Regen, Wind und Temperatur kombinieren (Knöpfe rechts)
- Fehler behoben: Regen blieb außerhalb Deutschlands sichtbar, obwohl „Regen“ abgewählt war

## 0.5.1 – Einstellungen aufgeräumt
- Knopf „Fertig“ oben entfernt – zurück geht es mit der Zurück-Geste
- „Hintergrund-Vorschau“ (Tag, Nacht, Nebel …) entfernt
- „Drive & Logging“ erscheint nur noch, wenn kein Drive-Ordner verbunden ist (Logging läuft weiter wie bisher)

## 0.5.0 – Orte
- Mehrere Orte: „Mein Standort“ ist immer die erste Seite, dahinter die gespeicherten Orte – auf der Hauptseite zwischen den Orten wischen
- Seitenpunkte unten in der Mitte (➤ = Mein Standort, heller Punkt = aktueller Ort); Tippen öffnet die Ortsliste
- Ortsliste: Suche beim Tippen, Karten mit Ortszeit, Wetterlage, Temperatur sowie Höchst-/Tiefstwert; Tippen öffnet den Ort
- Bearbeiten (langes Drücken oder „Bearbeiten“): Orte am Griff ≡ verschieben, mit ⊖ löschen
- Geladen wird nur der Ort, den man gerade ansieht (in der Ortsliste kurz alle) – wie bisher nur bei geöffneter App
- Ein früher einzeln gewählter Ort wird automatisch in die Liste übernommen

## 0.4.11 – Live-Karten in den Kacheln
- Kachel „Regenradar“ zeigt die echte Karte um den Ort mit dem aktuellen Live-Radar (DWD, weltweit RainViewer)
- Kachel „Windkarte“ zeigt die echte Karte mit Windfarben, wandernden Windlinien und Windwerten
- Beide nicht bedienbar (kein Verschieben/Zoomen) – Tippen öffnet wie gewohnt das Vollbild, langes Drücken bearbeitet die Kacheln

## 0.4.10 – Temperatur und Wind weltweit zuverlässig
- Beim weiten Herauszoomen blieb nur ein Rechteck mit Werten: Open-Meteo hatte wegen zu vieler Abrufe kurz abgelehnt
- Die App lädt jetzt ein doppelt so großes Gebiet wie sichtbar (weniger Nachladen) und hält ein Abruf-Budget ein – sie wartet kurz statt abgelehnt zu werden

## 0.4.9 – Neue Bedienung im Radar, Werte an Großstädten
- Oben keine Knöpfe mehr; unten rechts über der Zeitleiste eine Spalte mit Standort, Regen, Wind und Temperatur
- Regen, Wind und Temperatur sind Schalter und lassen sich kombinieren (z. B. Regen + Wind); mit Regen folgen Wind und Temperatur der Radar-Zeitleiste
- Großstädte, die die Karte gerade beschriftet, bekommen eigene Wind- und Temperaturwerte über dem Ortsnamen – weltweit, beim Hineinzoomen kommen weitere Städte dazu
- Werte bleiben frei von Titel, Knöpfen und Zeitleiste; Schließen mit der Zurück-Geste

## 0.4.8 – Temperaturkarte
- Neue Ebene „Temperatur“ im Vollbild-Radar (Ebenen-Knopf oben rechts): Farbfläche von kalt (blau) bis heiß (rot) und Werte wie „8°“ im gleichen Raster wie beim Wind, weltweit
- Zeitleiste stündlich bis +12 Stunden, Legende −10° bis 32°
- Die Temperaturen kommen im selben Abruf wie der Wind (keine zusätzlichen Abrufe)

## 0.4.7 – Windwerte auf der Karte
- Windkarte zeigt die Windgeschwindigkeit als schlichten Text („12 km/h“) in einem regelmäßigen, versetzten Raster – dichter beim Hineinzoomen, weiter beim Herauszoomen; die Werte stehen fest auf der Karte und springen beim Verschieben nicht
- Am eigenen Ort steht der Wert mit kleinem Punkt statt der Zahl im Kreis
- Einheit wie in den Einstellungen (km/h, m/s oder Bft)

## 0.4.6 – Radar vorgeladen
- Radarbilder und Winddaten werden schon beim Öffnen der App geladen; das Vollbild-Radar ist dadurch sofort bereit
- Wie bisher nur bei geöffneter App: Beim Verlassen wird das Laden abgebrochen

## 0.4.5 – Absturz beim Herauszoomen behoben, weniger Abrufe
- Absturz behoben: Beim sehr weiten Herauszoomen im Radar stürzte die App ab
- Open-Meteo zählt jeden Gitterpunkt als Abruf; die Windgitter sind jetzt kleiner (154 bzw. 100 Punkte), Nachladen höchstens alle 5 Sekunden, bei „zu viele Abrufe“ eine Minute Pause – sonst konnte sogar die normale Vorhersage kurz ausfallen
- Weltweites Radar lädt größere Kacheln und nur das aktuelle und nächste Bild – bleibt unter der Grenze von 100 Abrufen pro Minute
- Quellenangabe mit Link zu RainViewer (Info-Knopf auf der Karte)

## 0.4.4 – Radar und Wind weltweit
- Karte frei verschiebbar und zoombar, von der Weltkarte bis zur Stadt
- Regen weltweit: letzte 2 Stunden bis jetzt über RainViewer (10-Minuten-Schritte); in Deutschland weiterhin das genauere DWD-Radar
- Regen-Vorhersage außerhalb Deutschlands: Modell von Open-Meteo, stündlich bis +12 Stunden
- Wind weltweit mit echten Werten: Für den sichtbaren Ausschnitt lädt die App nach dem Verschieben oder Zoomen ein passendes Gitter nach

## 0.4.3 – Windlinien beim Zoomen
- Beim schnellen Heraus- oder Hineinzoomen werden die Windlinien sofort über den ganzen sichtbaren Bereich verteilt, statt im alten Ausschnitt hängen zu bleiben

## 0.4.2 – Live-Standort im Radar
- Blauer Punkt zeigt den eigenen Standort live auf der Karte (mit Genauigkeitskreis, sanft pulsierend)
- Neuer Knopf oben (Pfeil) schwenkt die Karte auf den eigenen Standort
- Der Standort wird nur abgefragt, solange das Radar offen ist

## 0.4.1 – Karte heller, Radar weicher
- Karte im Vollbild-Radar etwas heller (Hintergrund, Straßen, Grenzen, Schrift) – bleibt dunkel, ist aber besser lesbar
- Ortsnamen auf Deutsch (München statt MUNICH)
- Regenflächen weichgezeichnet statt eckiger Pixel
- Windkarte: größeres Gebiet, Farbfläche blendet am Rand weich aus statt als Viereck zu enden
- Windlinien bewegen sich beim Verschieben der Karte mit und füllen neue Bereiche sofort

## 0.4.0 – Radar und Karten
- Vollbild-Radar: dunkle Karte zum Verschieben und Zoomen (OpenFreeMap, Daten © OpenStreetMap), Ort mit aktueller Temperatur
- Zeitleiste mit Abspielen: DWD-Radar der letzten 2 Stunden, DWD-Radar-Vorhersage bis +2 Stunden (5-Minuten-Schritte), danach Modell-Niederschlag (ICON-EU) stündlich bis +12 Stunden
- Eigene Farbskala mit Legende: Leicht · Mäßig · Stark · Extrem (auch in der Regenradar-Kachel)
- Ebene „Wind“: Windgeschwindigkeit als Farbfläche und wandernde Strömungslinien, stündlich bis +12 Stunden (Open-Meteo, ICON)
- Öffnen über die Kachel Regenradar, die Kachel Windkarte oder den Karten-Knopf unten links
- Geladen wird nur, solange das Radar offen ist; beim Schließen oder Verlassen der App wird abgebrochen

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

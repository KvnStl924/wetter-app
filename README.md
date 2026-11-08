# Wetter-App 🌙

Persönliche Android-Wetter-App (APK) im Stil der iOS-Wetter-App, aber **immer im Dark Mode**.
Schwerpunkt: **heute** und **Deutschland mit Grenzgebieten**.

> **Status:** Planung abgeschlossen, der App-Code folgt.
> Der komplette Plan steht in **[docs/KONZEPT.md](docs/KONZEPT.md)**.

## Funktionen (Version 1)

- Wetter am aktuellen Standort, weitere Orte per Wischen
- Stündliche Vorhersage (Temperatur · Regen · Wind) mit Sonnenauf- und -untergang
- 10-Tage-Vorhersage mit Hinweis, wenn die Modelle unsicher sind
- Echtes DWD-Regenradar: 2 h zurück, 2 h Vorhersage, danach Modell bis 12 h
- Offizielle DWD-Unwetterwarnungen
- 12 Kacheln, per langem Drücken verschieb- und ausblendbar
- Dunkler Hintergrund, der sich Wetter und Tageszeit anpasst
  ([Vorschau](docs/hintergrund-vorschau.html))
- Logging mit automatischem Upload zu Google Drive, danach wird die Datei vom Handy gelöscht
- Update per Knopf in den Einstellungen, nie automatisch
- Kein Akkuverbrauch im Hintergrund: die App arbeitet nur, solange sie offen ist

## Technik

Kotlin · Jetpack Compose · MVVM · MapLibre · Open-Meteo (ICON-D2 + ECMWF) · DWD Open Data ·
Schrift Inter · min. Android 8.0

## APK bauen

```bash
./gradlew assembleDebug
```

GitHub Actions baut bei jedem Push eine Debug-APK (*Artifacts* im Workflow-Lauf).
Ein Tag `v*` (z. B. `v0.1.0`) erzeugt ein GitHub Release mit der APK. Daraus holt sich der
Update-Knopf der App die neue Version.

## Lizenz

[MIT](LICENSE) · Wetterdaten: [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0),
[Deutscher Wetterdienst](https://www.dwd.de/opendata) (Open Data)

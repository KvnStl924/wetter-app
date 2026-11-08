# Wetter-App 🌤️

Eine einfache, werbefreie Android-Wetter-App, geschrieben in **Kotlin** mit **Jetpack Compose**.
Die App wird als **APK** gebaut und über GitHub Releases verteilt.

> **Status:** Projekt-Setup. Der App-Code (Android-Projekt) folgt im nächsten Schritt.

## Geplante Funktionen

- Aktuelles Wetter am eigenen Standort (GPS)
- Städtesuche und Favoriten
- Stündliche Vorhersage (24 h) und 7-Tage-Vorhersage
- Temperatur, gefühlte Temperatur, Niederschlag, Wind, Luftfeuchtigkeit, UV-Index
- Hell-/Dunkelmodus, Einheiten °C/°F
- Offline-Cache der letzten Daten

Details: [docs/KONZEPT.md](docs/KONZEPT.md) · Fahrplan: [Issues & Milestones](../../milestones)

## Technik

| Bereich | Wahl |
|---|---|
| Sprache | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architektur | MVVM, Repository-Pattern |
| Netzwerk | Retrofit / Ktor + kotlinx.serialization |
| Wetterdaten | [Open-Meteo](https://open-meteo.com/) (kostenlos, kein API-Key) |
| Lokale Daten | Room / DataStore |
| Build | Gradle (Kotlin DSL), GitHub Actions |
| Min. Android | 8.0 (API 26) |

## APK bauen

Sobald das Android-Projekt angelegt ist:

```bash
./gradlew assembleDebug
```

Die APK liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`.

Außerdem baut **GitHub Actions** bei jedem Push automatisch eine Debug-APK
(zu finden im jeweiligen Workflow-Lauf unter *Artifacts*). Für einen Tag `v*`
(z. B. `v0.1.0`) wird ein GitHub Release mit der APK erstellt.

## Mitmachen

Bugs und Ideen bitte über die [Issue-Vorlagen](../../issues/new/choose) melden.

## Lizenz

[MIT](LICENSE) · Wetterdaten von [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0)

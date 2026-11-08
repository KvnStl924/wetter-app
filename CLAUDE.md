# Wetter-App – Hinweise für Claude

Android-App (Kotlin, Jetpack Compose) nur für Kevin. Plan: `docs/KONZEPT.md` (verbindlich).
Kevin ist Laie: Antworten einfach und auf Deutsch, selbst erledigen statt Anleitungen geben.

## Projekt
- Paket `de.schee.wetter`, minSdk 26, compileSdk 36. Lokal bauen: `./gradlew testDebugUnitTest assembleRelease`
  → `app/build/outputs/apk/release/Wetter-vX.Y.Z-release.apk`.
- **Version** nur in `app/build.gradle.kts` (`versionMajor/Minor/Patch`). Schema X.Y.Z:
  X = großes Update (neue Funktion, neue Oberfläche), Y = kleines Update, Z = Fehlerbehebung.
  Aufbauphase: jede Phase erhöht Y (Phase 1 = 0.1.0 … Phase 6 = 0.6.0), danach 1.0.0.
  `versionCode` = X·10000 + Y·100 + Z (automatisch).
- **Veröffentlichen:** Eintrag in `CHANGELOG.md` („## X.Y.Z – Titel“), Version erhöhen, committen,
  Tag `vX.Y.Z` pushen → GitHub Actions baut, signiert, erstellt das Release (APK + .sha256).
  Der Update-Knopf der App liest `releases/latest`.
- **Signierschlüssel:** `keystore/wetter-release.jks` + `keystore/keystore.properties` (nicht im Git).
  Sicherung: Drive „Apps › Wetter-App › 07 Signierschlüssel - privat“, in GitHub als Secrets
  `SIGNING_*`. Zertifikat-SHA-256 `03:EB:D0:39:64:19:5C:30:99:A4:B2:67:4D:CC:34:AE:5C:32:FA:8E:C3:F4:55:EB:3F:70:7A:01:22:BE:2A:F0`.
  Anderer Schlüssel = Android nimmt kein Update an.

## Regeln von Kevin
- **Keine Hintergrundaktivität.** Alles nur bei geöffneter App (Standort, Daten, Animation).
  Kein WorkManager-Zeitplan, kein Service. Animationen über `repeatOnLifecycle(STARTED)`.
- **Logging** (`log/AppLog.kt`): startet beim Öffnen, protokolliert jeden Tipp (`AppLog.tap("Was → Ziel")`),
  jede Datenabfrage (`AppLog.data`), Fehler. Beim Verlassen Upload nach Drive
  „03 Logs - Fehlerberichte/Logs“ (`log/DriveSync.kt`), danach lokal löschen. Offline: beim nächsten Öffnen.
  Max. 20 MB lokal. Neue Bedienelemente müssen ihren Tipp loggen.
- **Updates nur per Knopf** in den Einstellungen (`update/Updater.kt`), nie automatisch.
- **Immer Dark Mode.** Hintergrund `ui/sky/SkyBackground.kt` entspricht der abgenommenen Vorschau
  `docs/hintergrund-vorschau.html` (prozedurale Wolken/Nebel – einfache Flecken fand Kevin „billig“).
- Stil wie iOS-Wetter-App, aber eigene Symbole (`ui/icons/WeatherIcon.kt`) und Schrift Inter (OFL).

## Prüfen ohne Handy
- Bildschirmfotos (Robolectric): `WETTER_SCREENSHOTS=<ordner> ./gradlew testDebugUnitTest --tests '*ScreenshotDump'`
  → `main_top.png`, `main_full.png` (Testdaten aus `app/src/test/resources`). Endlos-Animationen sind dort über
  `LocalAnimate provides false` aus, sonst hängt Robolectric. Neue Endlos-Animationen müssen `LocalAnimate` beachten.
- Kein Emulator: der Windows-Hypervisor-Treiber fehlt (bräuchte Admin).

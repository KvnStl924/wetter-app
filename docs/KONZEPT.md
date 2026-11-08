# Konzept: Wetter-App

## Ziel
Eine schlanke, schnelle Wetter-App für Android ohne Werbung, Tracking oder Account.

## Funktionen (MVP – Version 0.1)
1. **Startbildschirm** – aktuelles Wetter am Standort: Temperatur, Wettersymbol, Beschreibung, Min/Max.
2. **Stündliche Vorhersage** – horizontale Liste der nächsten 24 Stunden.
3. **7-Tage-Vorhersage** – Liste mit Min/Max, Symbol, Regenwahrscheinlichkeit.
4. **Standort** – GPS-Berechtigung; Fallback: manuelle Stadtsuche.
5. **Fehlerzustände** – kein Internet, keine Berechtigung, API-Fehler.

## Später (Version 0.2+)
- Favoriten-Städte (wischen zwischen Orten)
- Detailwerte: Wind, Luftfeuchtigkeit, Luftdruck, UV-Index, Sonnenauf-/untergang
- Einstellungen: °C/°F, km/h / m/s, Sprache
- Offline-Cache, Pull-to-refresh
- Home-Screen-Widget
- Wetter-Warnungen / Benachrichtigungen

## Datenquelle: Open-Meteo
- Vorhersage: `https://api.open-meteo.com/v1/forecast`
  - Parameter z. B. `latitude`, `longitude`,
    `current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m`,
    `hourly=temperature_2m,weather_code,precipitation_probability`,
    `daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,sunrise,sunset`,
    `timezone=auto`
- Ortssuche: `https://geocoding-api.open-meteo.com/v1/search?name=Berlin&language=de`
- Wettercodes nach WMO (0 = klar, 1–3 = bewölkt, 45/48 = Nebel, 51–67 = Regen, 71–77 = Schnee, 95–99 = Gewitter)
- Kein API-Key nötig; Namensnennung „Weather data by Open-Meteo.com“ in der App.

## Architektur
```
ui/            Compose-Screens + ViewModels
domain/        Modelle (Weather, Forecast, Location)
data/
  remote/      Open-Meteo-API (Retrofit/Ktor)
  local/       Room/DataStore (Cache, Favoriten, Einstellungen)
  repository/  WeatherRepository
location/      FusedLocationProvider-Wrapper
```

## Build & Verteilung
- Debug-APK bei jedem Push (GitHub Actions → Artifacts)
- Release-APK bei Tag `v*` → GitHub Release
- Signierung der Release-APK über GitHub-Secrets (Keystore als Base64), nie im Repo

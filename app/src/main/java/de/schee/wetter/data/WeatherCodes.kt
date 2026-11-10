package de.schee.wetter.data

import de.schee.wetter.ui.icons.IconKind
import de.schee.wetter.ui.sky.SkyTime
import de.schee.wetter.ui.sky.SkyWeather
import java.time.LocalDateTime


/** WMO-Wettercodes (Open-Meteo) → Text, Symbol, Hintergrund. */
object WeatherCodes {
    fun text(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "Sonnig" else "Klar"
        1 -> if (isDay) "Überwiegend sonnig" else "Überwiegend klar"
        2 -> "Teils bewölkt"
        3 -> "Bedeckt"
        45, 48 -> "Nebel"
        51, 53 -> "Nieselregen"
        55 -> "Starker Nieselregen"
        56, 57 -> "Gefrierender Niesel"
        61 -> "Leichter Regen"
        63 -> "Regen"
        65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"
        71 -> "Leichter Schneefall"
        73 -> "Schneefall"
        75 -> "Starker Schneefall"
        77 -> "Schneegriesel"
        80 -> "Leichte Schauer"
        81 -> "Regenschauer"
        82 -> "Starke Schauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "Unbekannt"
    }

    fun icon(code: Int, isDay: Boolean): IconKind = when (code) {
        0 -> if (isDay) IconKind.SUN else IconKind.MOON
        1, 2 -> if (isDay) IconKind.SUN_CLOUD else IconKind.MOON_CLOUD
        3 -> IconKind.CLOUD
        45, 48 -> IconKind.FOG
        in 51..67, in 80..82 -> IconKind.RAIN
        in 71..77, 85, 86 -> IconKind.SNOW
        in 95..99 -> IconKind.STORM
        else -> IconKind.CLOUD
    }

    fun sky(code: Int): SkyWeather = when (code) {
        0, 1 -> SkyWeather.CLEAR
        2 -> SkyWeather.PARTLY
        3 -> SkyWeather.OVERCAST
        45, 48 -> SkyWeather.FOG
        in 51..67, in 80..82 -> SkyWeather.RAIN
        in 71..77, 85, 86 -> SkyWeather.SNOW
        in 95..99 -> SkyWeather.STORM
        else -> SkyWeather.PARTLY
    }

    /** Tageszeit für den Hintergrund aus echten Sonnenzeiten. */
    fun skyTime(now: LocalDateTime, sunrise: LocalDateTime?, sunset: LocalDateTime?): SkyTime {
        if (sunrise == null || sunset == null) return if (now.hour in 7..17) SkyTime.DAY else SkyTime.NIGHT
        return when {
            now.isAfter(sunrise.minusMinutes(30)) && now.isBefore(sunrise.plusMinutes(45)) -> SkyTime.DAWN
            now.isAfter(sunset.minusMinutes(45)) && now.isBefore(sunset.plusMinutes(30)) -> SkyTime.DUSK
            now.isAfter(sunrise) && now.isBefore(sunset) -> SkyTime.DAY
            else -> SkyTime.NIGHT
        }
    }
}

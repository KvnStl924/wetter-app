package de.schee.wetter.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View


/**
 * Fein abgestimmte Vibration („Premium-Haptik“, Kevin am 10.11.2026): keine einfachen Brummer, sondern die
 * kurzen, knackigen Bausteine des Vibrationsmotors (Android 11+, z. B. Samsung Galaxy S). Fehlen sie,
 * nimmt die App die feinen System-Rückmeldungen – nie das lange Standard-Vibrieren.
 */
object Haptics {
    enum class Kind {
        /** Zeitleiste: ein Schritt (sehr leicht). */
        TICK,
        /** Zeitleiste: volle Stunde oder „Jetzt“ (etwas deutlicher). */
        HOUR,
        /** Antippen eines Knopfes, einer Kachel oder Zeile: ganz kurz und leicht. */
        TAP,
        /** Umschalten (Ebene, Seite, Schalter). */
        SNAP,
        /** Langes Drücken (Bearbeiten, Ort merken). */
        PRESS,
        /** Erledigt (gespeichert, aktualisiert): kurzes Doppel-Klicken, das ausklingt. */
        SUCCESS,
        /** Gewitter kommt näher: leises Grollen, das anschwillt und mit einem satten Schlag endet. */
        THUNDER,
    }

    private var last = 0L
    private var vibrator: Vibrator? = null

    private fun vib(ctx: Context): Vibrator? = vibrator ?: runCatching {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }.getOrNull()?.also { vibrator = it }

    fun play(view: View, kind: Kind) {
        val now = System.currentTimeMillis()
        // Schnelles Wischen: höchstens alle 28 ms ein Ticken, sonst verschwimmt es zu einem Brummen
        if ((kind == Kind.TICK || kind == Kind.HOUR) && now - last < 28) return
        last = now
        val v = vib(view.context)
        if (Build.VERSION.SDK_INT >= 30 && v != null && v.hasVibrator()) {
            val c = VibrationEffect.startComposition()
            val ok = when (kind) {
                Kind.TICK -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, .55f) || prim(v, c, VibrationEffect.Composition.PRIMITIVE_TICK, .3f)
                Kind.HOUR -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_TICK, .85f)
                Kind.TAP -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_TICK, .5f)
                Kind.SNAP -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_CLICK, .55f)
                Kind.PRESS -> (Build.VERSION.SDK_INT >= 31 && prim(v, c, VibrationEffect.Composition.PRIMITIVE_THUD, .6f)) ||
                    prim(v, c, VibrationEffect.Composition.PRIMITIVE_CLICK, .9f)
                Kind.SUCCESS -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_CLICK, .7f) &&
                    prim(v, c, VibrationEffect.Composition.PRIMITIVE_TICK, .45f, delayMs = 70)
                Kind.THUNDER -> v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK) && run {
                    // Grollen: immer dichter und kräftiger werdendes Ticken …
                    listOf(0 to .25f, 55 to .35f, 45 to .45f, 35 to .6f, 30 to .75f).forEach { (d, s) -> prim(v, c, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, s, d) }
                    // … dann der Schlag und ein kurzes Nachhallen
                    (Build.VERSION.SDK_INT >= 31 && prim(v, c, VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 30)) || prim(v, c, VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 30)
                    prim(v, c, VibrationEffect.Composition.PRIMITIVE_LOW_TICK, .35f, 140)
                    true
                }
            }
            if (ok) { runCatching { v.vibrate(c.compose()) }.onSuccess { return } }
        }
        // Ersatz: feine System-Rückmeldung
        val constant = when (kind) {
            Kind.TICK -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Kind.HOUR -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Kind.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
            Kind.SNAP -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.VIRTUAL_KEY
            Kind.PRESS -> HapticFeedbackConstants.LONG_PRESS
            Kind.SUCCESS -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Kind.THUNDER -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    private fun prim(v: Vibrator, c: VibrationEffect.Composition, id: Int, scale: Float, delayMs: Int = 0): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !v.areAllPrimitivesSupported(id)) return false
        c.addPrimitive(id, scale, delayMs); return true
    }
}

/** Kurzform in Compose: `val haptic = rememberHaptics(); haptic(Haptics.Kind.SNAP)` */
@androidx.compose.runtime.Composable
fun rememberHaptics(): (Haptics.Kind) -> Unit {
    val view = androidx.compose.ui.platform.LocalView.current
    return androidx.compose.runtime.remember(view) { { k: Haptics.Kind -> Haptics.play(view, k) } }
}

package de.schee.wetter.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset


/**
 * Bewegungen wie bei iOS: Federn ohne Nachwippen statt starrer Zeitkurven, Einstellungen und Ortsliste schieben
 * sich von rechts herein (die Hauptseite weicht ein Stück zurück), die Karte gleitet von unten hoch.
 */
object Motion {
    /** iOS-Standardkurve (schnell los, weich aus). */
    val Ease = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
    fun <T> soft(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)
    private val slide = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = IntOffset(1, 1))

    private fun push(s: String) = s == "settings" || s == "places"
    private fun modal(s: String) = s == "radar" || s == "wind" || s == "warn" || s == "bolt"

    fun screen(from: String, to: String): ContentTransform = when {
        // vorwärts: neue Seite von rechts, Hauptseite weicht um ein Drittel nach links und dunkelt ab
        from == "main" && push(to) ->
            slideInHorizontally(slide) { it } togetherWith
                (slideOutHorizontally(slide) { -it / 3 } + fadeOut(tween(300, easing = Ease), targetAlpha = .55f))
        // zurück
        push(from) && to == "main" ->
            (slideInHorizontally(slide) { -it / 3 } + fadeIn(tween(300, easing = Ease), initialAlpha = .55f)) togetherWith
                slideOutHorizontally(slide) { it }
        // Karte: gleitet von unten hoch, die Hauptseite tritt leicht zurück
        modal(to) ->
            (slideInVertically(slide) { it } + fadeIn(tween(160))) togetherWith
                (scaleOut(tween(380, easing = Ease), targetScale = .94f) + fadeOut(tween(380, easing = Ease), targetAlpha = .4f))
        modal(from) ->
            (scaleIn(tween(380, easing = Ease), initialScale = .94f) + fadeIn(tween(380, easing = Ease), initialAlpha = .4f)) togetherWith
                (slideOutVertically(slide) { it } + fadeOut(tween(260, delayMillis = 120)))
        else -> fadeIn(tween(240, easing = Ease)) togetherWith fadeOut(tween(200, easing = Ease))
    }.also { it.targetContentZIndex = if (push(to) || modal(to)) 1f else 0f }
}

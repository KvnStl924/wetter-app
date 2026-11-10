package de.schee.wetter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import de.schee.wetter.R


/** Inter (SIL OFL) – frei nutzbarer Ersatz für Apples SF Pro. */
val Inter = FontFamily(
    Font(R.font.inter_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

object WColors {
    val Text = Color(0xFFF2F5FA)
    val TextDim = Color(0xC7EBF0FA)      // ~78 %
    val Label = Color(0x8CEBF0FA)        // ~55 %, Kachel-Überschriften
    val Card = Color(0x6B0E121E)         // halbtransparentes Dunkel
    val CardBorder = Color(0x0FFFFFFF)
    val Glass = Color(0x1FFFFFFF)
    val Sun = Color(0xFFFFCC33)
    val Cloud = Color(0xFFF2F4FB)
    val Rain = Color(0xFF5FB6FF)
    val Accent = Color(0xFF7FB2FF)
    val Good = Color(0xFF30D158)
    val Warn = Color(0xFFFF9F0A)
    val Bad = Color(0xFFFF453A)
}

object WType {
    val bigTemp = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraLight, fontSize = 96.sp, color = WColors.Text)
    val city = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 32.sp, color = WColors.Text)
    val condition = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = WColors.Text)
    val highLow = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = WColors.Text)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.6.sp, color = WColors.Label)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, color = WColors.Text)
    val bodyDim = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, color = WColors.TextDim)
    val value = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Light, fontSize = 34.sp, color = WColors.Text)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = WColors.Text)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, color = WColors.Text)
}

@Composable
fun WetterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WColors.Accent, background = Color.Black, surface = Color(0xFF1C1C1E),
            onPrimary = Color.Black, onBackground = WColors.Text, onSurface = WColors.Text,
        ),
        typography = MaterialTheme.typography.run {
            copy(
                bodyLarge = bodyLarge.copy(fontFamily = Inter), bodyMedium = bodyMedium.copy(fontFamily = Inter),
                titleLarge = titleLarge.copy(fontFamily = Inter), labelLarge = labelLarge.copy(fontFamily = Inter),
            )
        },
        content = content,
    )
}

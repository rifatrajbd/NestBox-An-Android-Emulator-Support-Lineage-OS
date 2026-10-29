package app.nestbox.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import app.nestbox.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** UI font: Roboto Flex 400/500/600 (bundled; the phone has no Google Play fonts). */
val RobotoFlex = FontFamily(
    variable(R.font.roboto_flex, 400),
    variable(R.font.roboto_flex, 500),
    variable(R.font.roboto_flex, 600),
)

/** Mono font: JetBrains Mono 400/500 for specs, paths and chips. */
val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500),
)

fun ui(size: TextUnit, weight: Int = 400, color: androidx.compose.ui.graphics.Color = Nb.Text) =
    TextStyle(fontFamily = RobotoFlex, fontSize = size, fontWeight = FontWeight(weight), color = color)

fun mono(size: TextUnit, weight: Int = 400, color: androidx.compose.ui.graphics.Color = Nb.Text3) =
    TextStyle(fontFamily = JetBrainsMono, fontSize = size, fontWeight = FontWeight(weight), color = color)

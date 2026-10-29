package app.nestbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val scheme = darkColorScheme(
    primary = Nb.Accent,
    onPrimary = Nb.OnAccent,
    primaryContainer = Nb.AccentContainer,
    background = Nb.Bg,
    onBackground = Nb.Text,
    surface = Nb.Surface,
    onSurface = Nb.Text,
    surfaceVariant = Nb.Surface2,
    onSurfaceVariant = Nb.Text3,
    outline = Nb.Outline,
    error = Nb.Red,
)

@Composable
fun NestboxTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = RobotoFlex),
            bodyMedium = base.bodyMedium.copy(fontFamily = RobotoFlex),
            labelLarge = base.labelLarge.copy(fontFamily = RobotoFlex),
        ),
        content = content,
    )
}

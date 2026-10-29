package app.nestbox.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens from design/README.md §4. Keep values exact.
 * OKLCH colors are converted to sRGB hex (the README's "≈" hex values are rounder approximations).
 */
object Nb {
    // Backgrounds
    val Bg = Color(0xFF111318)
    val BgDeep = Color(0xFF0C0E12)
    val Console = Color(0xFF08090A)

    // Surfaces
    val Surface = Color(0xFF1B1F27)
    val Surface2 = Color(0xFF232833)
    val Surface3 = Color(0xFF2B303A)
    val Hover = Color(0xFF222733)
    val Divider = Color(0xFF252A33)
    val Outline = Color(0xFF3A4050)

    // Text
    val Text = Color(0xFFECEDEE)
    val Text2 = Color(0xFFC4C6CA)
    val Text3 = Color(0xFF9A9DA3)
    val Text4 = Color(0xFF6B6E73)

    // Accent
    val Accent = Color(0xFF4C8DFF)
    val OnAccent = Color(0xFF00204D)
    val AccentContainer = Color(0xFF1F2F4D)

    // Status
    val Amber = Color(0xFFF5B75B)        // oklch(0.82 0.13 75)
    val Green = Color(0xFF9FD6A8)
    val Red = Color(0xFFF07F77)          // oklch(0.72 0.14 25)
    val PoweredOff = Color(0xFF5B5E63)

    // Component-specific values used in the design
    val ChipBg = Color(0xFF262B36)
    val ChipText = Color(0xFFB9C3D6)
    val BannerBg = Color(0xFF3C2A0E)     // oklch(0.3 0.05 75)
    val BannerFg = Color(0xFFFFCC79)     // oklch(0.88 0.12 75)
    val BannerSub = Color(0xFFD8D2C4)
    val BannerOnFg = Color(0xFF2A1D00)
    val BetaTag = Color(0xFFFCC270)      // oklch(0.85 0.12 75)
    val HealthChip = Color(0xFF161A21)
    val ConsoleText = Color(0xFF9FBCF0)
    val ConsoleTrack = Color(0xFF1F2022)
    val SegmentActive = Color(0xFF2F3A52)
    val Stripe1 = Color(0xFF1F2430)
    val Stripe2 = Color(0xFF262B37)
    val GuestStripe1 = Color(0xFF141820)
    val GuestStripe2 = Color(0xFF191D26)
    val MapBg = Color(0xFF171B23)
    val MapLine = Color(0xFF1E2330)
    val FloatHome = Color(0xFF1A1D24)
    val FloatIcon = Color(0xFF262A33)
    val BallBg = Color(0xEB4C8DFF)       // rgba(76,141,255,.92)
    val BallMenu = Color(0xF71B1F27)     // rgba(27,31,39,.97)
    val CoordsChip = Color(0xD90A0C10)   // rgba(10,12,16,.85)
    val JoyBg = Color(0xD91B1F27)        // rgba(27,31,39,.85)
    val ToggleOffTrack = Outline
    val ToastBg = Text
    val ToastText = Bg

    // Radius
    val RPill = 999.dp
    val R24 = 24.dp
    val R20 = 20.dp
    val R16 = 16.dp
    val R12 = 12.dp
    val R8 = 8.dp
    val R6 = 6.dp

    // Type scale
    val T28 = 28.sp
    val T26 = 26.sp
    val T24 = 24.sp
    val T16 = 16.sp
    val T15 = 15.sp
    val T14 = 14.sp
    val T13_5 = 13.5.sp
    val T13 = 13.sp
    val T12_5 = 12.5.sp
    val T12 = 12.sp
    val T11_5 = 11.5.sp
    val T11 = 11.sp
    val T10_5 = 10.5.sp
    val T10 = 10.sp

    val Touch = 44.dp
}

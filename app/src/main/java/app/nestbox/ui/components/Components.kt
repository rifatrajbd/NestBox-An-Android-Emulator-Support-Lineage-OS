package app.nestbox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nestbox.ui.theme.Nb
import app.nestbox.ui.theme.mono
import app.nestbox.ui.theme.ui

/** 135° stripes, like the CSS repeating-linear-gradient placeholders in the design. */
fun Modifier.stripes(a: Color, b: Color, band: Dp): Modifier = drawBehind {
    drawRect(a)
    val w = band.toPx()
    // Bands run bottom-left to top-right; along x the period is 2w·√2 so the perpendicular period is 2w.
    val step = w * 2f * 1.4142f
    clipRect {
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(b, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = w)
            x += step
        }
    }
}

/** 48×28 switch from the design: track accent / outline, knob on-accent / text-3. */
@Composable
fun NbToggle(on: Boolean) {
    val track by animateColorAsState(if (on) Nb.Accent else Nb.ToggleOffTrack, tween(150), label = "track")
    val knob by animateColorAsState(if (on) Nb.OnAccent else Nb.Text3, tween(150), label = "knob")
    val x by animateDpAsState(if (on) 24.dp else 4.dp, tween(150), label = "x")
    Box(Modifier.size(48.dp, 28.dp).clip(RoundedCornerShape(20.dp)).background(track)) {
        Box(Modifier.offset(x = x, y = 4.dp).size(20.dp).clip(CircleShape).background(knob))
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun NbIcon(icon: ImageVector, size: Dp = 24.dp, tint: Color = Nb.Text) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(size), tint = tint)
}

/** 44×44 icon button. */
@Composable
fun IconBtn(icon: ImageVector, tint: Color = Nb.Text, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(Nb.Touch).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { NbIcon(icon, size, tint) }
}

/** Back button row with an optional title and mono trailing label. */
@Composable
fun BackBar(
    icon: ImageVector = NbIcons.Back,
    title: String? = null,
    trailing: String? = null,
    trailingSize: androidx.compose.ui.unit.TextUnit = Nb.T11,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconBtn(icon, onClick = onBack)
        if (title != null) Text(title, style = ui(Nb.T16, 500), modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = mono(trailingSize), modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(Nb.RPill)).background(Nb.Accent).clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) NbIcon(icon, 22.dp, Nb.OnAccent)
        Text(text, style = ui(Nb.T16, 600, Nb.OnAccent))
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.height(52.dp).clip(RoundedCornerShape(Nb.RPill)).background(Nb.Surface3).clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) NbIcon(icon, 20.dp)
        Text(text, style = ui(Nb.T14, 500))
    }
}

/** Small pill button, e.g. "Fix", "Copy", "Resume". */
@Composable
fun PillButton(text: String, bg: Color = Nb.Surface3, fg: Color = Nb.Text, weight: Int = 500, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(Nb.RPill)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = ui(Nb.T13, weight, fg)) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ui(Nb.T13, 400, Nb.Text3), modifier = modifier.padding(horizontal = 4.dp))
}

/** Rounded list card. Rows inside are separated by 1 px divider lines. */
@Composable
fun ListCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Nb.R20)).background(Nb.Surface), content = content)
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Nb.Divider))
}

/** Renders [items] as card rows with dividers between them. */
@Composable
fun <T> CardRows(items: List<T>, row: @Composable (T) -> Unit) {
    ListCard {
        items.forEachIndexed { i, item ->
            if (i > 0) Divider()
            row(item)
        }
    }
}

/** Icon + title + sub + trailing row used by toggles, settings and maintenance. */
@Composable
fun InfoRow(
    icon: ImageVector?,
    title: String,
    sub: String? = null,
    titleColor: Color = Nb.Text,
    titleWeight: Int = 500,
    iconTint: Color = Nb.Text3,
    titleSize: androidx.compose.ui.unit.TextUnit = Nb.T14,
    padV: Dp = 13.dp,
    subMono: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    @OptIn(ExperimentalFoundationApi::class)
    val click = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(click).padding(horizontal = 14.dp, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) NbIcon(icon, 20.dp, iconTint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ui(titleSize, titleWeight, titleColor))
            if (sub != null) Text(
                sub,
                style = if (subMono) mono(Nb.T11) else ui(Nb.T12, 400, Nb.Text3).copy(lineHeight = Nb.T12 * 1.35),
            )
        }
        trailing()
    }
}

/** Key / mono value row (review, spoof details). */
@Composable
fun KeyValueRow(k: String, v: String, kSize: androidx.compose.ui.unit.TextUnit = Nb.T14, vSize: androidx.compose.ui.unit.TextUnit = Nb.T13) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(k, style = ui(kSize, 400, Nb.Text3))
        Spacer(Modifier.weight(1f))
        Text(v, style = mono(vSize, 400, Nb.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Choice chip: 38 px tall, 1.5 px border, accent when selected. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, isMono: Boolean = false, squared: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (squared) Nb.R12 else Nb.RPill)
    Box(
        Modifier.height(38.dp).clip(shape)
            .background(if (selected) Nb.AccentContainer else Nb.Surface)
            .border(BorderStroke(1.5.dp, if (selected) Nb.Accent else Nb.Surface3), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (squared) 12.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = if (isMono) mono(if (squared) Nb.T13 else Nb.T12_5, 500, Nb.Text) else ui(Nb.T13, 500))
    }
}

/** Selectable card with a 1.5 px border (wizard ROMs and presets). */
@Composable
fun SelectCard(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(Nb.R20)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Nb.Surface)
            .border(BorderStroke(1.5.dp, if (selected) Nb.Accent else Nb.Surface3), shape)
            .clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Mono tag such as ROOT or STABLE. */
@Composable
fun Tag(text: String, color: Color = Nb.ChipText, small: Boolean = false) {
    Box(
        Modifier.clip(RoundedCornerShape(if (small) 5.dp else Nb.R6)).background(Nb.ChipBg)
            .padding(horizontal = if (small) 6.dp else 7.dp, vertical = if (small) 1.dp else 2.dp),
    ) { Text(text, style = mono(if (small) Nb.T10 else Nb.T10_5, 400, color)) }
}

/** White toast card at the bottom; the view model hides it after 2.2 s. */
@Composable
fun ToastCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .shadow(24.dp, RoundedCornerShape(Nb.R12))
            .clip(RoundedCornerShape(Nb.R12)).background(Nb.ToastBg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text, style = ui(Nb.T14, 400, Nb.ToastText)) }
}

/** Thin progress bar. */
@Composable
fun Bar(fraction: Float, track: Color, fill: Color, height: Dp, radius: Dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius)).background(track)) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).heightIn(min = height).clip(RoundedCornerShape(radius))
                .background(fill)
        )
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

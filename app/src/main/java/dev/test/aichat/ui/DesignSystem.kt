package dev.test.aichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ChatColors {
    val Accent = Color(0xFF6554E8)
    val AccentDark = Color(0xFF4433AB)
    val Ink = Color(0xFF242638)
    val Secondary = Color(0xFF85879A)
    val Background = Color(0xFFF7F8FC)
    val Line = Color(0xFFEAEBF2)
    val Lavender = Color(0xFFF0EDFF)
    val Green = Color(0xFF42957B)
    val Error = Color(0xFFCE5261)
    val ErrorBackground = Color(0xFFFFF2F3)
    val Gradient = Brush.linearGradient(listOf(Color(0xFF7867F1), Color(0xFF5441CD)))
}

@Composable
internal fun AppIconButton(icon: ImageVector, label: String, onClick: () -> Unit, background: Color = Color.White, tint: Color = ChatColors.Ink, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, tween(110), label = "iconPress")
    IconButton(onClick = onClick, enabled = enabled, interactionSource = interaction, modifier = Modifier.size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(15.dp)).background(background)) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (enabled) tint else Color(0xFFC0C1CD))
    }
}

@Composable
internal fun AiMark(size: Int = 56, filled: Boolean = true) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .3f).dp)).then(if (filled) Modifier.background(ChatColors.Gradient) else Modifier.background(ChatColors.Lavender)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size((size * .48f).dp), tint = if (filled) Color.White else ChatColors.Accent)
    }
}

@Composable
internal fun PageHeader(title: String, subtitle: String? = null, leading: @Composable () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ChatColors.Ink)
            subtitle?.let { Text(it, Modifier.padding(top = 2.dp), fontSize = 11.sp, color = ChatColors.Secondary, maxLines = 1) }
        }
        trailing()
    }
}

@Composable
internal fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 5.dp, top = 23.dp, bottom = 10.dp), fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Secondary)
}

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(110), label = "buttonPress")
    Button(onClick, modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }, enabled = enabled, interactionSource = interaction, shape = RoundedCornerShape(19.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ChatColors.Accent, disabledContainerColor = ChatColors.Line, disabledContentColor = ChatColors.Secondary), contentPadding = PaddingValues(horizontal = 20.dp)) {
        icon?.let { Icon(it, null, Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)) }
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SmallPill(text: String, color: Color = ChatColors.Lavender, textColor: Color = ChatColors.Accent) {
    Box(Modifier.background(color, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)) { Text(text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .4.sp) }
}

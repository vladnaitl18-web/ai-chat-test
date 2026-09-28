package dev.test.aichat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.test.aichat.BuildConfig
import dev.test.aichat.billing.BillingState
import dev.test.aichat.data.Account

@Composable
internal fun SettingsScreen(account: Account, billing: BillingState, onClose: () -> Unit, onPaywall: () -> Unit, onRestore: () -> Unit, onDocument: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PageHeader("Настройки", leading = { AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onClose) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(ChatColors.Gradient)) {
                Box(Modifier.size(170.dp).align(Alignment.TopEnd).offset(x = 70.dp, y = (-45).dp).background(Color.White.copy(alpha = .07f), CircleShape))
                Box(Modifier.size(110.dp).align(Alignment.TopEnd).offset(x = 20.dp, y = (-55).dp).background(Color.White.copy(alpha = .05f), CircleShape))
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(21.dp), tint = Color.White)
                        Text("AI Чат", Modifier.padding(start = 9.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f)); SmallPill("PRO", Color.White.copy(alpha = .2f), Color.White)
                    }
                    Text(if (account.subscribed) "Больше свободы.\nМеньше ограничений." else "Для всех ваших\n«а что, если…»", Modifier.padding(top = 20.dp), fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, letterSpacing = (-.5).sp)
                    Text(if (account.subscribed) "Ваша подписка активна" else "Общайтесь с помощником без лимита", Modifier.padding(top = 9.dp), color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                    if (!account.subscribed) Button(onPaywall, Modifier.fillMaxWidth().padding(top = 20.dp).height(45.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ChatColors.AccentDark)) {
                        Text("Открыть подписку", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.ArrowForward, null, Modifier.size(17.dp))
                    }
                }
            }
            if (!account.subscribed) Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 5.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(15.dp), tint = ChatColors.Secondary)
                Text("Бесплатный доступ", Modifier.weight(1f).padding(start = 8.dp), fontSize = 11.sp, color = ChatColors.Secondary)
                Text(if (account.loaded) "Осталось ${account.remaining} из 3" else "Подключаемся…", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ChatColors.Accent)
            }
            SectionCaption("Подписка")
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
                Column {
                    SettingsRow(Icons.Default.Restore, "Восстановить покупки", "Найти подписку в Google Play", onRestore, enabled = !billing.loading)
                    RowDivider()
                    SettingsRow(Icons.Default.CreditCard, "Управлять подпиской", "Тариф, продление и отмена", { openLink(context, "https://play.google.com/store/account/subscriptions") })
                }
            }
            if (billing.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(4.dp)), color = ChatColors.Accent, trackColor = ChatColors.Lavender)
            billing.error?.let { Text(it, Modifier.padding(horizontal = 6.dp, vertical = 12.dp), color = ChatColors.Error, fontSize = 11.sp, lineHeight = 17.sp) }
            billing.notice?.let { Text(it, Modifier.padding(horizontal = 6.dp, vertical = 12.dp), color = ChatColors.Green, fontSize = 11.sp) }
            SectionCaption("Приложение")
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
                Column {
                    SettingsRow(Icons.Default.StarOutline, "Оценить приложение", "Ваш отзыв помогает нам стать лучше", { openLink(context, "market://details?id=${context.packageName}") }, iconColor = Color(0xFFBA8A39), iconBackground = Color(0xFFFFF6E5))
                    RowDivider()
                    SettingsRow(Icons.Default.Shield, "Конфиденциальность", "Как мы обращаемся с данными", { onDocument("Конфиденциальность") }, iconColor = ChatColors.Green, iconBackground = Color(0xFFECF6F1))
                    RowDivider()
                    SettingsRow(Icons.Default.Description, "Условия", "Правила использования сервиса", { onDocument("Условия") })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { AiMark(22, false); Text("AI Чат", Modifier.padding(start = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Secondary) }
            Text("Версия ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 26.dp), fontSize = 10.sp, textAlign = TextAlign.Center, color = ChatColors.Secondary)
        }
    }
}

@Composable
private fun RowDivider() { HorizontalDivider(Modifier.padding(start = 65.dp, end = 16.dp), color = ChatColors.Line, thickness = .5.dp) }

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true, iconColor: Color = ChatColors.Accent, iconBackground: Color = ChatColors.Lavender) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(iconBackground, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = iconColor) }
        Column(Modifier.weight(1f).padding(start = 13.dp, end = 5.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ChatColors.Ink)
            Text(subtitle, Modifier.padding(top = 4.dp), fontSize = 10.sp, lineHeight = 15.sp, color = ChatColors.Secondary)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp), tint = ChatColors.Secondary.copy(alpha = .6f))
    }
}

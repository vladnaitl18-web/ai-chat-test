package dev.test.aichat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.test.aichat.billing.BillingState
import dev.test.aichat.billing.Plan
import dev.test.aichat.data.Account

@Composable
internal fun PaywallScreen(state: BillingState, account: Account, onClose: () -> Unit, onRestore: () -> Unit, onReload: () -> Unit, onPurchase: (Plan) -> Unit, onDocument: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf("chat_monthly") }
    val plan = state.plans.find { it.id == selected } ?: state.plans.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(Icons.Default.Close, "Закрыть пейвол", onClose)
            Spacer(Modifier.weight(1f)); SmallPill("AI ЧАТ PRO")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(78.dp).background(ChatColors.Lavender.copy(alpha = .5f), CircleShape))
                Text("PRO", color = ChatColors.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
            }
            Text("Безлимитный доступ", Modifier.padding(top = 16.dp), fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp, textAlign = TextAlign.Center)
            Text(if (account.remaining <= 0) "Хороший разговор не должен\nзаканчиваться из-за лимита." else "Больше пространства для ваших\nвопросов, идей и планов.", Modifier.padding(top = 12.dp, bottom = 25.dp), color = ChatColors.Secondary, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            state.error?.let {
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(19.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(24.dp), tint = ChatColors.Secondary)
                        Text("Предложения пока недоступны", Modifier.padding(top = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(it, Modifier.padding(top = 7.dp), fontSize = 11.sp, lineHeight = 17.sp, color = ChatColors.Secondary, textAlign = TextAlign.Center)
                        TextButton(onReload, enabled = !state.loading, modifier = Modifier.padding(top = 4.dp)) { Icon(Icons.Default.Refresh, null, Modifier.size(15.dp)); Text("Обновить тарифы", Modifier.padding(start = 6.dp), fontSize = 12.sp) }
                    }
                }
            }
            if (state.error != null) Spacer(Modifier.height(18.dp))
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                    listOf("Вопросы без ограничений", "Все диалоги под рукой", "Ответы с удобным оформлением").forEach { benefit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(22.dp).background(ChatColors.Lavender, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = ChatColors.Accent) }
                            Text(benefit, Modifier.padding(start = 11.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(23.dp))
            if (state.loading) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) { CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp); Text("Загружаем предложения…", Modifier.padding(top = 11.dp), fontSize = 11.sp, color = ChatColors.Secondary) }
            if (state.plans.any { it.isPreview }) {
                SmallPill("ТЕСТОВЫЕ ЦЕНЫ")
                Text("Google Play пока не вернул тарифы.\nПоказаны цены из макета, оплата недоступна.", Modifier.padding(top = 8.dp, bottom = 14.dp), color = ChatColors.Secondary, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
            }
            state.plans.forEach { item ->
                val checked = item.id == plan?.id
                Surface(onClick = { selected = item.id }, color = if (checked) ChatColors.Lavender else Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(if (checked) 1.5.dp else 1.dp, if (checked) ChatColors.Accent else ChatColors.Line), modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp)) {
                    Row(Modifier.padding(horizontal = 15.dp, vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(21.dp).background(if (checked) ChatColors.Accent else Color.Transparent, CircleShape).border(1.5.dp, if (checked) ChatColors.Accent else ChatColors.Line, CircleShape), contentAlignment = Alignment.Center) { if (checked) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("Отмена в любой момент", Modifier.padding(top = 4.dp), fontSize = 10.sp, color = ChatColors.Secondary) }
                        Column(horizontalAlignment = Alignment.End) { Text(item.price, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); Text(item.period, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = ChatColors.Secondary) }
                    }
                }
            }
            state.notice?.let { Text(it, Modifier.padding(15.dp), color = ChatColors.Green, textAlign = TextAlign.Center, fontSize = 12.sp) }
            Spacer(Modifier.height(20.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            PrimaryButton("Продолжить", { plan?.let(onPurchase) }, Modifier.padding(top = 12.dp), enabled = plan?.details != null && !state.loading)
            OutlinedButton(onRestore, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).heightIn(min = 48.dp),
                shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ChatColors.Accent.copy(alpha = .35f))) {
                Icon(Icons.Default.Restore, null, Modifier.size(19.dp))
                Text("Восстановить покупки", Modifier.padding(start = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), horizontalArrangement = Arrangement.Center) {
                Text("Условия", Modifier.clickable { onDocument("Условия") }.padding(7.dp), color = ChatColors.Secondary, fontSize = 9.sp)
                Text("·", Modifier.padding(7.dp), color = ChatColors.Secondary)
                Text("Конфиденциальность", Modifier.clickable { onDocument("Конфиденциальность") }.padding(7.dp), color = ChatColors.Secondary, fontSize = 9.sp)
            }
        }
    }
}

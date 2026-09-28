package dev.test.aichat.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(vm: ChatViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val billing by vm.billing.state.collectAsStateWithLifecycle()
    var document by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(ui.screen, ui.chatId) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = ChatColors.Accent, background = ChatColors.Background, surface = Color.White, onSurface = ChatColors.Ink, onBackground = ChatColors.Ink, surfaceTint = Color.Transparent)) {
        Surface(Modifier.fillMaxSize(), color = ChatColors.Background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                if (!ui.onboarded) WelcomeScreen(vm::finishOnboarding) { document = "Конфиденциальность" }
                else AnimatedContent(targetState = ui.screen, label = "screen", modifier = Modifier.fillMaxSize(),
                    transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 24 }) togetherWith fadeOut(tween(120)) }) { screen ->
                    when (screen) {
                    Screen.CHAT -> ChatScreen(ui, chats.firstOrNull { it.chat.id == ui.chatId }?.chat?.title ?: "Новый чат", messages, account,
                        onMenu = { vm.navigate(Screen.HISTORY) }, onNew = { vm.openChat(null) }, onSend = vm::send, onStop = vm::stop, onRetry = vm::retry,
                        onPresented = vm::responsePresented, onReconnect = vm::connect, onPaywall = { vm.navigate(Screen.PAYWALL) })
                    Screen.HISTORY -> HistoryScreen(chats, ui.chatId, onClose = { vm.navigate(Screen.CHAT) }, onOpen = vm::openChat,
                        onNew = { vm.openChat(null) }, onSettings = { vm.navigate(Screen.SETTINGS) }, onDelete = vm::deleteChat)
                    Screen.PAYWALL -> PaywallScreen(billing, account, onClose = { vm.navigate(Screen.CHAT) }, onRestore = vm::restore,
                        onReload = { vm.navigate(Screen.PAYWALL) }, onPurchase = { vm.billing.purchase(context as Activity, it) }, onDocument = { document = it })
                    Screen.SETTINGS -> SettingsScreen(account, billing, onClose = { vm.navigate(Screen.HISTORY) }, onPaywall = { vm.navigate(Screen.PAYWALL) }, onRestore = vm::restore, onDocument = { document = it })
                    }
                }
            }
        }
        BackHandler(ui.onboarded && ui.screen != Screen.CHAT) { vm.navigate(if (ui.screen == Screen.SETTINGS) Screen.HISTORY else Screen.CHAT) }
        document?.let { title ->
            ModalBottomSheet(onDismissRequest = { document = null }, containerColor = Color.White, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                    Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (title == "Конфиденциальность")
                        "Ваши диалоги сохраняются на устройстве. Для входа используется случайный идентификатор установки — без номера телефона и электронной почты.\n\nЧтобы подготовить ответ, мы передаём текст текущего диалога через наш сервер в OpenRouter. У сервиса есть собственная политика обработки данных. Не отправляйте информацию, которую не хотите ему передавать.\n\nУдаление данных приложения очищает локальную историю. Покупки обрабатывает Google Play. На сервере сохраняются идентификатор установки, остаток бесплатных сообщений и статус подписки.\n\nЭто тестовая версия приложения. Информация о владельце и контакты поддержки будут добавлены перед публикацией."
                    else "Это тестовая версия AI-помощника. Ответы могут содержать ошибки — проверяйте важную информацию.\n\nПосле бесплатного лимита доступна подписка. Её стоимость, период и условия продления отображаются в Google Play перед оплатой. Управлять подпиской и отменить её можно в настройках Google Play.\n\nИнформация о владельце сервиса, контакты поддержки и окончательные условия будут добавлены перед публикацией.",
                        Modifier.padding(top = 20.dp, bottom = 20.dp), fontSize = 14.sp, lineHeight = 23.sp, color = ChatColors.Secondary)
                    if (title == "Конфиденциальность") TextButton(onClick = { openLink(context, "https://openrouter.ai/privacy") }) { Text("Политика OpenRouter"); Icon(Icons.Default.OpenInNew, null, Modifier.padding(start = 8.dp).size(14.dp)) }
                    PrimaryButton("Понятно", { document = null })
                }
            }
        }
    }
}

internal fun openLink(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (_: Exception) { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onPrivacy: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { AiMark(30); Text("AI Чат", Modifier.padding(start = 10.dp), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(40.dp))
            AiMark(92)
            Spacer(Modifier.height(28.dp))
            SmallPill("МЕСТО ДЛЯ ВАШИХ ИДЕЙ")
            Text("Хорошие идеи\nначинаются\nс вопроса.", Modifier.padding(top = 18.dp), fontSize = 35.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = (-1).sp)
            Text("Помощник для планов, текстов\nи маленьких открытий каждый день.", Modifier.padding(top = 18.dp), textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 23.sp, color = ChatColors.Secondary)
            Spacer(Modifier.height(28.dp))
            listOf(Icons.Default.AutoAwesome to "3 сообщения, чтобы попробовать", Icons.Default.History to "Ваши диалоги всегда под рукой", Icons.Default.Lock to "История хранится на устройстве").forEach { (icon, text) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(20.dp), tint = ChatColors.Accent); Text(text, Modifier.padding(start = 13.dp), fontSize = 13.sp) }
            }
            Spacer(Modifier.height(26.dp))
        }
        PrimaryButton("Начать", onStart, icon = Icons.Default.ArrowForward)
        TextButton(onPrivacy, Modifier.padding(top = 6.dp)) { Text("Как мы обрабатываем данные", fontSize = 11.sp, color = ChatColors.Secondary) }
    }
}

package dev.test.aichat.ui

import android.widget.TextView
import android.widget.Toast
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.test.aichat.data.Account
import dev.test.aichat.data.MessageEntity
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.delay

internal val suggestions = listOf(
    Triple(Icons.Default.Explore, "Спланировать поездку", "Составь план поездки в Стамбул на 3 дня"),
    Triple(Icons.Default.Lightbulb, "Разобраться в сложном", "Объясни простыми словами, что такое SSE"),
    Triple(Icons.Default.EditNote, "Подобрать слова", "Напиши вежливый ответ на письмо"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatScreen(ui: UiState, title: String, messages: List<MessageEntity>, account: Account, onMenu: () -> Unit, onNew: () -> Unit,
                       onSend: (String) -> Unit, onPresented: (String) -> Unit, onStop: () -> Unit, onRetry: (String) -> Unit, onReconnect: () -> Unit, onPaywall: () -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable(ui.chatId) { mutableStateOf("") }
    var showSuggestions by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (messages.isEmpty()) "AI Чат" else title, if (ui.sending) "Готовлю ответ для вас" else "Ваш помощник на каждый день",
            leading = { AppIconButton(Icons.Default.Menu, "Список чатов", onMenu) },
            trailing = { if (messages.isNotEmpty()) AppIconButton(Icons.Default.Add, "Новый чат", onNew, ChatColors.Lavender, ChatColors.Accent) })
        val displayedError = remember { mutableStateOf("") }
        LaunchedEffect(ui.error) { ui.error?.let { displayedError.value = it } }
        AnimatedVisibility(ui.error != null, enter = fadeIn(tween(180)) + expandVertically(tween(220)), exit = fadeOut(tween(120)) + shrinkVertically(tween(180))) {
            ErrorNotice(displayedError.value, onReconnect, !ui.connecting, Modifier.padding(horizontal = 20.dp), "Подключиться снова")
        }
        if (messages.isEmpty() && imeVisible) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Напишите свой вопрос", color = ChatColors.Secondary, fontSize = 13.sp)
            }
        } else if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.height(8.dp))
                AiMark(42)
                Text("Чем могу\nпомочь сегодня?", Modifier.padding(top = 14.dp), fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp, color = ChatColors.Ink)
                Text("Для больших планов и маленьких вопросов.", Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Secondary)
                Spacer(Modifier.height(18.dp))
                Text("ПОПРОБУЙТЕ СПРОСИТЬ", fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Secondary)
                Spacer(Modifier.height(12.dp))
                suggestions.forEach { (icon, label, prompt) ->
                    Surface(onClick = { input = prompt }, color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(20.dp), tint = ChatColors.Accent)
                            Text(label, Modifier.weight(1f).padding(start = 12.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.Default.NorthWest, null, Modifier.size(15.dp), tint = ChatColors.Secondary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            val list = rememberLazyListState()
            val dragged by list.interactionSource.collectIsDraggedAsState()
            var follow by remember(ui.chatId) { mutableStateOf(true) }
            LaunchedEffect(dragged) { if (dragged) follow = false else if (!list.canScrollForward) follow = true }
            val last = messages.lastOrNull()
            LaunchedEffect(messages.size, last?.content?.length, last?.status, follow,
                list.layoutInfo.viewportEndOffset, list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == messages.lastIndex }?.size) {
                if (follow) {
                    val size = list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == messages.lastIndex }?.size ?: 0
                    list.scrollToItem(messages.lastIndex.coerceAtLeast(0), size)
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
                    items(messages, key = { it.id }) { message ->
                        Box(Modifier.animateItem(fadeInSpec = tween(240), placementSpec = tween(240), fadeOutSpec = tween(160))) {
                        if (message.role == "user") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = ChatColors.Accent, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), modifier = Modifier.widthIn(max = 290.dp)) {
                                Text(message.content, Modifier.padding(horizontal = 17.dp, vertical = 14.dp), color = Color.White, fontSize = 15.sp, lineHeight = 23.sp)
                            }
                        } else AssistantMessage(message, ui.sending, message.id == ui.presentingId, { onPresented(message.id) }) { onRetry(message.id) }
                        }
                    }
                    item { Spacer(Modifier.height(2.dp)) }
                }
                if (!follow && list.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    AppIconButton(Icons.Default.ArrowDownward, "К последнему сообщению", { follow = true }, ChatColors.Lavender, ChatColors.Accent)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(ChatColors.Background).padding(horizontal = 16.dp).padding(bottom = if (imeVisible) 6.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(if (account.loaded) ChatColors.Green else ChatColors.Secondary, CircleShape))
                val counter = when {
                    ui.connecting -> "Подключаемся…"
                    !account.loaded -> "Нет соединения"
                    account.subscribed -> "Безлимитный доступ"
                    ui.sending -> "Можно остановить в любой момент"
                    else -> "${account.remaining} ${messageCount(account.remaining)}"
                }
                AnimatedContent(counter, modifier = Modifier.weight(1f).padding(start = 7.dp), label = "messageCounter",
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }) {
                    Text(it, color = ChatColors.Secondary, fontSize = 10.sp)
                }
                if (!account.subscribed) Text("Попробовать Pro", Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPaywall).padding(horizontal = 5.dp, vertical = 4.dp), color = ChatColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, border = BorderStroke(1.dp, ChatColors.Line), shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (messages.isNotEmpty()) IconButton({ showSuggestions = true }, Modifier.size(38.dp)) { Icon(Icons.Default.Lightbulb, "Подсказки для сообщения", tint = ChatColors.Secondary, modifier = Modifier.size(22.dp)) }
                    BasicTextField(value = input, onValueChange = { if (it.length <= 10000) input = it }, modifier = Modifier.weight(1f).padding(horizontal = 5.dp).heightIn(min = 40.dp).semantics { contentDescription = "Сообщение" },
                        maxLines = if (imeVisible) 4 else 6, enabled = !ui.sending, textStyle = TextStyle(color = ChatColors.Ink, fontSize = 14.sp, lineHeight = 21.sp), cursorBrush = SolidColor(ChatColors.Accent),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (input.isEmpty()) Text(if (ui.sending) "Готовлю ответ…" else "Спросите о чём угодно…", fontSize = 13.sp, color = ChatColors.Secondary); inner() } })
                    if (ui.sending) AppIconButton(Icons.Default.Stop, "Стоп", onStop, ChatColors.Ink, Color.White)
                    else {
                        val enabled = input.isNotBlank() && account.loaded && !ui.connecting
                        AppIconButton(Icons.Default.ArrowUpward, "Отправить", { keyboard?.hide(); onSend(input); if (account.subscribed || account.remaining > 0) input = "" }, if (enabled) ChatColors.Accent else ChatColors.Background, Color.White, enabled)
                    }
                }
            }
            if (!imeVisible) Text("AI может ошибаться. Проверяйте важное.", Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 9.dp), color = ChatColors.Secondary.copy(alpha = .8f), fontSize = 9.sp, textAlign = TextAlign.Center)
        }
    }
    if (showSuggestions) ModalBottomSheet(onDismissRequest = { showSuggestions = false }, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
            Text("С чего начнём?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Выберите идею и добавьте свои детали.", Modifier.padding(top = 8.dp, bottom = 20.dp), color = ChatColors.Secondary, fontSize = 13.sp)
            suggestions.forEach { (icon, label, prompt) ->
                ListItem(headlineContent = { Text(label, fontSize = 14.sp) }, leadingContent = { Icon(icon, null, tint = ChatColors.Accent) }, trailingContent = { Icon(Icons.Default.NorthWest, null, Modifier.size(16.dp)) }, modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable { input = prompt; showSuggestions = false }, colors = ListItemDefaults.colors(containerColor = Color.White))
            }
        }
    }
}
internal fun messageCount(count: Int) = when { count % 100 in 11..14 -> "бесплатных сообщений"; count % 10 == 1 -> "бесплатное сообщение"; count % 10 in 2..4 -> "бесплатных сообщения"; else -> "бесплатных сообщений" }

@Composable
private fun AssistantMessage(message: MessageEntity, sending: Boolean, animate: Boolean, onPresented: () -> Unit, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            AiMark(28)
            Text("AI Чат", Modifier.padding(start = 9.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Ink)
            if (message.status == "streaming") Text("·  отвечает", Modifier.padding(start = 6.dp), fontSize = 11.sp, color = ChatColors.Secondary)
        }
        Surface(color = Color.White, shape = RoundedCornerShape(4.dp, 22.dp, 22.dp, 22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                if (message.content.isNotEmpty()) Markdown(message.content, message.status == "streaming", animate, onPresented)
                if (message.status == "streaming") Row(Modifier.padding(top = if (message.content.isEmpty()) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Text(message.error ?: if (message.content.isEmpty()) "Обдумываю ваш вопрос…" else "Продолжаю…", Modifier.padding(start = 9.dp), fontSize = 11.sp, color = ChatColors.Secondary)
                }
                if (message.status in listOf("stopped", "superseded")) Text(if (message.status == "stopped") "Вы остановили ответ" else "Предыдущий ответ прерван", Modifier.padding(top = 12.dp), fontSize = 11.sp, color = ChatColors.Secondary)
                if (message.status in listOf("error", "failed")) ErrorNotice((if (message.content.isNotEmpty()) "Часть ответа сохранена.\n" else "") + (message.error ?: "Проверьте сеть и повторите."), onRetry, !sending, Modifier.padding(top = if (message.content.isEmpty()) 0.dp else 15.dp))
            }
        }
        if (message.content.isNotBlank() && message.status != "streaming") Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ clipboard.setText(AnnotatedString(message.content)); Toast.makeText(context, "Ответ скопирован", Toast.LENGTH_SHORT).show() }, Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Скопировать ответ", Modifier.size(15.dp), tint = ChatColors.Secondary) }
            Text("Сохранено в истории", fontSize = 10.sp, color = ChatColors.Secondary)
        }
    }
}

@Composable
internal fun ErrorNotice(message: String, onRetry: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, action: String = "Повторить") {
    Surface(modifier.fillMaxWidth(), color = ChatColors.ErrorBackground, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.WifiOff, null, Modifier.size(17.dp), tint = ChatColors.Error); Text("Не удалось завершить запрос", Modifier.padding(start = 8.dp), color = ChatColors.Error, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
            Text(message, Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = ChatColors.Secondary)
            TextButton(onRetry, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(34.dp)) { Icon(Icons.Default.Refresh, null, Modifier.size(15.dp), tint = ChatColors.Error); Text(action, Modifier.padding(start = 5.dp), color = ChatColors.Error, fontSize = 12.sp) }
        }
    }
}

@Composable
internal fun Markdown(text: String, streaming: Boolean, animate: Boolean, onPresented: () -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    var shown by remember { mutableIntStateOf(if (animate) 0 else text.length) }
    var cursorVisible by remember { mutableStateOf(true) }
    val latestText by rememberUpdatedState(text)
    val finished by rememberUpdatedState(onPresented)
    LaunchedEffect(text, animate) {
        if (!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()) shown = text.length
    }
    LaunchedEffect(animate) {
        if (!animate || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            shown = latestText.length
        } else while (true) {
            val previous = latestText.getOrNull(shown - 1)
            delay(when (previous) { '.', '!', '?', '\n' -> 110L; ',', ';', ':' -> 65L; else -> 32L })
            val target = latestText
            if (shown < target.length) {
                // One Unicode code point per tick; incoming SSE bursts never accelerate presentation.
                shown = target.offsetByCodePoints(shown, 1)
            }
        }
    }
    LaunchedEffect(shown, text, streaming) {
        if (!streaming && shown >= text.length) finished()
    }
    val typing = streaming || shown < text.length
    LaunchedEffect(typing) {
        cursorVisible = true
        while (typing) { delay(500); cursorVisible = !cursorVisible }
    }
    val markwon = remember(context) { Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1.05f, 1f, 1f))
                .headingBreakHeight(0).blockMargin((14 * density).toInt()).bulletWidth((4 * density).toInt()).linkColor(ChatColors.Accent.toArgb())
        }
    }).build() }
    val visibleText = text.take(shown.coerceAtMost(text.length))
    val formatted = remember(markwon, visibleText) { markwon.toMarkdown(visibleText) }
    AndroidView(factory = { TextView(it).apply {
        textSize = 15f; setTextColor(ChatColors.Ink.toArgb()); setLineSpacing(4 * density, 1.05f)
        setTextIsSelectable(true); includeFontPadding = false
    } }, update = { view ->
        val content = SpannableStringBuilder(formatted)
        if (typing) {
            content.append(" ▍")
            content.setSpan(ForegroundColorSpan(if (cursorVisible) ChatColors.Accent.toArgb() else Color.Transparent.toArgb()), content.length - 1, content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        markwon.setParsedMarkdown(view, content)
    }, modifier = Modifier.fillMaxWidth())
}

package dev.test.aichat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.test.aichat.data.ChatSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryScreen(chats: List<ChatSummary>, selected: String?, onClose: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onDelete: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    val today = LocalDate.now()
    val grouped = chats.filter { it.chat.title.contains(query, ignoreCase = true) }.groupBy {
        when (Instant.ofEpochMilli(it.chat.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate()) { today -> "Сегодня"; today.minusDays(1) -> "Вчера"; else -> "Ранее" }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Мои чаты", "Идеи, к которым можно вернуться", leading = { AppIconButton(Icons.Default.Close, "Закрыть список", onClose) }, trailing = { AppIconButton(Icons.Default.Settings, "Настройки", onSettings) })
        TextField(query, { query = it }, placeholder = { Text("Поиск по чатам", color = ChatColors.Secondary, fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = ChatColors.Secondary) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Очистить поиск", Modifier.size(17.dp)) } },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp),
            colors = TextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (grouped.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = if (imeVisible) 12.dp else 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!imeVisible) AiMark(64, false)
                    Text(if (query.isEmpty()) "Всё начинается с вопроса" else "Ничего не найдено", Modifier.padding(top = 20.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (!imeVisible) Text(if (query.isEmpty()) "Начните первый диалог.\nОн сохранится здесь автоматически." else "Попробуйте другое название\nили более короткий запрос.", Modifier.padding(top = 10.dp), textAlign = TextAlign.Center, color = ChatColors.Secondary, lineHeight = 21.sp, fontSize = 13.sp)
                }
            }
            grouped.forEach { (group, groupChats) ->
                item(key = group) { SectionCaption(group) }
                items(groupChats, key = { it.chat.id }) { item ->
                    val active = item.chat.id == selected
                    Surface(onClick = { onOpen(item.chat.id) }, shape = RoundedCornerShape(21.dp), color = if (active) ChatColors.Lavender else Color.White,
                        border = if (active) BorderStroke(1.dp, ChatColors.Accent.copy(alpha = .15f)) else null) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(35.dp).background(if (active) Color.White.copy(alpha = .7f) else ChatColors.Background, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(17.dp), tint = if (active) ChatColors.Accent else ChatColors.Secondary) }
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                val date = Instant.ofEpochMilli(item.chat.updatedAt).atZone(ZoneId.systemDefault())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(if (group == "Сегодня") date.format(DateTimeFormatter.ofPattern("HH:mm")) else date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))), color = ChatColors.Secondary, fontSize = 9.sp, modifier = Modifier.padding(start = 8.dp))
                                }
                                Text(item.preview.orEmpty().replace(Regex("[#*`\\n]"), " ").replace(Regex(" +"), " ").trim(), Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = ChatColors.Secondary, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            IconButton(onClick = { deleting = item }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.DeleteOutline, "Удалить чат «${item.chat.title}»", Modifier.size(20.dp), tint = ChatColors.Secondary)
                            }
                        }
                    }
                }
            }
        }
        PrimaryButton("Новый чат", onNew, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), icon = Icons.Default.Add)
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить чат?") },
            text = { Text("«${item.chat.title}» и все сообщения будут удалены с этого устройства.") },
            confirmButton = { TextButton(onClick = { onDelete(item.chat.id); deleting = null }) { Text("Удалить", color = ChatColors.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
    }
}

package dev.filebe

import android.os.Environment
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class NameAction(val title: String, val initial: String = "", val perform: (String) -> Unit)
private data class Confirmation(val message: String, val perform: () -> Unit)

@Composable fun FilesScreen(state: UiState, vm: FilebeViewModel, allFiles: Boolean, requestPermission: () -> Unit) {
    var naming by remember { mutableStateOf<NameAction?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var properties by remember { mutableStateOf<Entry?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    val deviceRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    LaunchedEffect(allFiles, state.folder) { if (allFiles && state.folder == null) vm.shared() }

    if (!allFiles) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().glass().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Text("Файлы устройства", style = MaterialTheme.typography.titleLarge)
                Text("Разрешите доступ к общей памяти, чтобы открыть документы и папки телефона. После выдачи доступа это сообщение исчезнет.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = requestPermission) { Text("Разрешить доступ") }
                Text("Доступ включается в системных настройках Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val folder = state.folder
    if (folder == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (folder.id != deviceRoot) vm.up() }, enabled = !state.busy && folder.id != deviceRoot) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
            }
            Text(folder.id.removePrefix(deviceRoot).ifBlank { "Память устройства" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { vm.refresh() }, enabled = !state.busy) { Icon(Icons.Outlined.Refresh, "Обновить") }
            IconButton(onClick = { vm.downloads() }, enabled = !state.busy) { Icon(Icons.Outlined.Download, "Загрузки браузера (Filbe/Downloads)") }
            Box {
                IconButton(onClick = { createMenu = true }, enabled = !state.busy) { Icon(Icons.Outlined.Add, "Создать") }
                DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                    DropdownMenuItem(text = { Text("Текстовый файл") }, onClick = { createMenu = false; naming = NameAction("Создать файл", "script.kt") { vm.make(it, false) } })
                    DropdownMenuItem(text = { Text("Папку") }, onClick = { createMenu = false; naming = NameAction("Создать папку", "project") { vm.make(it, true) } })
                }
            }
        }
        OutlinedTextField(value = state.query, onValueChange = vm::query, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), singleLine = true,
            enabled = !state.busy, label = { Text("Поиск по имени") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${state.entries.size} объектов", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Подпапки", style = MaterialTheme.typography.labelSmall)
                Switch(checked = state.recursive, onCheckedChange = vm::recursive, enabled = !state.busy)
            }
        }
        state.clipboard?.let { clipboard ->
            Row(Modifier.fillMaxWidth().glass().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !state.busy, onClick = {
                    if (clipboard.move) confirm = Confirmation("Переместить «${clipboard.entry.name}» сюда? Исходник будет удалён после успешного копирования.") { vm.paste() }
                    else vm.paste()
                }, modifier = Modifier.weight(1f)) { Text(if (clipboard.move) "Вставить и переместить" else "Вставить: ${clipboard.entry.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = vm::cancelClipboard) { Icon(Icons.Outlined.Close, "Очистить буфер") }
            }
        }
        if (state.entries.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Папка пуста", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(state.entries, key = { it.ref.id }) { entry ->
                    FileRow(entry, !state.busy, { if (entry.directory) vm.home(entry.ref.id) else vm.open(entry) }) { command ->
                        when (command) {
                            "copy" -> vm.copy(entry, false)
                            "move" -> vm.copy(entry, true)
                            "rename" -> naming = NameAction("Переименовать", entry.name) { vm.rename(entry, it) }
                            "delete" -> confirm = Confirmation("Безвозвратно удалить «${entry.name}»${if (entry.directory) " и всё содержимое" else ""}? Это не корзина.") { vm.delete(entry) }
                            "zip" -> vm.zip(entry)
                            "properties" -> properties = entry
                        }
                    }
                }
            }
        }
    }
    naming?.let { action ->
        var name by remember(action) { mutableStateOf(action.initial) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(action.title) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Имя") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { action.perform(name); naming = null }) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Отмена") } })
    }
    confirm?.let { action -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Подтверждение") }, text = { Text(action.message) },
        confirmButton = { TextButton(onClick = { action.perform(); confirm = null }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }) }
    properties?.let { entry -> AlertDialog(onDismissRequest = { properties = null }, title = { Text(entry.name) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer { Text("Путь: ${entry.ref.id}\n\nТип: ${if (entry.directory) "папка" else "файл"}\nРазмер: ${if (entry.directory) "—" else prettySize(entry.size)}\nИзменён: ${if (entry.modified > 0) DateFormat.format("yyyy-MM-dd HH:mm:ss", entry.modified) else "неизвестно"}") } },
        confirmButton = { TextButton(onClick = { properties = null }) { Text("Закрыть") } }) }
}

@Composable private fun FileRow(entry: Entry, enabled: Boolean, open: () -> Unit, action: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).glass().clickable(enabled = enabled, onClick = open).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp).size(19.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(if (entry.directory) "Папка" else prettySize(entry.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.MoreVert, "Действия") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("copy" to "Копировать", "move" to "Переместить", "rename" to "Переименовать", "zip" to "Создать ZIP", "properties" to "Свойства", "delete" to "Удалить").forEach { (command, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; action(command) })
                }
            }
        }
    }
}

private fun prettySize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble(); var index = -1
    do { value /= 1024.0; index++ } while (value >= 1024.0 && index < units.lastIndex)
    return "%.1f %s".format(java.util.Locale.getDefault(), value, units[index])
}

package dev.filebe

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SyntaxTransformation(private val name: String) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 60000) return TransformedText(text, OffsetMapping.Identity)
        val output = AnnotatedString.Builder(text)
        for (token in CodeTools.tokens(text.text, name)) {
            val color = when (token.kind) {
                CodeTools.Kind.COMMENT -> Color(0xFF6A9955)
                CodeTools.Kind.STRING -> Color(0xFFCE9178)
                CodeTools.Kind.NUMBER -> Color(0xFFB5CEA8)
                CodeTools.Kind.KEYWORD -> Color(0xFF569CD6)
            }
            output.addStyle(SpanStyle(color = color), token.start, token.end)
        }
        return TransformedText(output.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable fun EditorScreen(state: UiState, vm: FilebeViewModel) {
    val typography = MaterialTheme.typography
    val editorColors = darkColorScheme(
        primary = Color(0xFFD4D4D4), onPrimary = Color(0xFF1E1E1E), secondary = Color(0xFFBDBDBD),
        background = Color(0xFF1E1E1E), onBackground = Color(0xFFD4D4D4), surface = Color(0xFF252526), onSurface = Color(0xFFD4D4D4),
        surfaceVariant = Color(0xFF2D2D30), onSurfaceVariant = Color(0xFFBDBDBD), outline = Color(0xFF4B4B4B), outlineVariant = Color(0xFF383838)
    )
    MaterialTheme(colorScheme = editorColors, typography = typography) { EditorContent(state, vm) }
}

@Composable private fun EditorContent(state: UiState, vm: FilebeViewModel) {
    val document = state.opened
    var confirmSave by remember { mutableStateOf(false) }
    if (document == null) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Редактор кода", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Откройте текстовый файл во вкладке «Файлы». Поддерживаются UTF-8 и подсветка основных языковых конструкций.", color = Color(0xFFD4D4D4))
        }
        return
    }
    var value by remember(document.entry.ref.id) { mutableStateOf(TextFieldValue(document.text)) }
    LaunchedEffect(document.entry.ref.id, document.text) {
        if (value.text != document.text) value = TextFieldValue(document.text, TextRange(document.text.length))
    }
    var showSearch by remember(document.entry.ref.id) { mutableStateOf(false) }
    var findText by remember(document.entry.ref.id) { mutableStateOf("") }
    var replaceText by remember(document.entry.ref.id) { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val syntax = remember(document.entry.name) { SyntaxTransformation(document.entry.name) }
    val editorBackground = Color(0xFF1E1E1E)
    val lineCount = remember(value.text) { (value.text.count { it == '\n' } + 1).coerceAtMost(10000) }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val byteCount = remember(value.text) { value.text.toByteArray(Charsets.UTF_8).size }

    fun commit(newValue: TextFieldValue) { value = newValue; vm.edit(newValue.text) }
    fun copyText() {
        val range = value.selection
        val selected = if (range.collapsed) value.text else value.text.substring(range.min, range.max)
        if (selected.isNotEmpty()) clipboard.setText(AnnotatedString(selected))
    }
    fun pasteText() {
        val pasted = clipboard.getText()?.text ?: return
        val start = value.selection.min; val end = value.selection.max
        val next = value.text.replaceRange(start, end, pasted)
        commit(TextFieldValue(next, TextRange(start + pasted.length)))
    }
    fun selectAllText() { value = value.copy(selection = TextRange(0, value.text.length)) }
    fun findNext() {
        if (findText.isBlank()) return
        val start = if (value.selection.collapsed || value.selection.end >= value.text.length) 0 else value.selection.end
        val index = value.text.indexOf(findText, startIndex = start, ignoreCase = true)
        if (index >= 0) value = value.copy(selection = TextRange(index, index + findText.length))
        else vm.notify("Совпадений не найдено")
    }
    fun replaceCurrent() {
        if (findText.isBlank()) return
        val selection = value.selection
        val selected = if (selection.collapsed) "" else value.text.substring(selection.min, selection.max)
        val matchStart = if (selected.equals(findText, ignoreCase = true)) selection.min else value.text.indexOf(findText, ignoreCase = true)
        if (matchStart < 0) { vm.notify("Совпадений не найдено"); return }
        val newText = value.text.replaceRange(matchStart, matchStart + findText.length, replaceText)
        commit(TextFieldValue(newText, TextRange(matchStart + replaceText.length)))
    }
    fun replaceAll() {
        if (findText.isBlank()) return
        val newText = value.text.replace(findText, replaceText, ignoreCase = true)
        if (newText == value.text) vm.notify("Совпадений не найдено") else {
            commit(TextFieldValue(newText, TextRange(newText.length)))
            vm.notify("Замена выполнена")
        }
    }

    Column(Modifier.fillMaxSize().background(editorBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(document.entry.name + if (document.dirty) "  •" else "", maxLines = 1, style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("UTF-8 · $byteCount Б · ${document.entry.ref.id}", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9E9E9E))
            }
            IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Outlined.Search, "Найти и заменить", tint = Color(0xFFDCDCAA)) }
            TextButton(onClick = { vm.tab(Tab.BROWSER) }) { Text("Браузер") }
            Button(enabled = document.dirty && !state.busy, onClick = { confirmSave = true }) { Text("Сохранить") }
        }
        if (showSearch) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 8.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(findText, { findText = it }, Modifier.weight(1f), singleLine = true, label = { Text("Найти") })
                    TextButton(onClick = { findNext(); focusRequester.requestFocus() }) { Text("Далее") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replaceText, { replaceText = it }, Modifier.weight(1f), singleLine = true, label = { Text("Заменить на") })
                    TextButton(onClick = { replaceCurrent() }) { Text("Заменить") }
                    TextButton(onClick = { replaceAll() }) { Text("Все") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF252526)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::copyText) { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Копировать") }
            TextButton(onClick = ::pasteText) { Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Вставить") }
            TextButton(onClick = ::selectAllText) { Icon(Icons.Outlined.SelectAll, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Всё") }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).background(editorBackground).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text(lineNumbers, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp),
                color = Color(0xFF858585), modifier = Modifier.padding(start = 4.dp, end = 10.dp))
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                BasicTextField(value = value, onValueChange = ::commit, enabled = !state.busy,
                    modifier = Modifier.widthIn(min = 500.dp).focusRequester(focusRequester).padding(bottom = 32.dp),
                    textStyle = TextStyle(color = Color(0xFFD4D4D4), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    cursorBrush = SolidColor(Color(0xFFD4D4D4)), visualTransformation = syntax,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 145.dp).background(Color(0xFF252526)).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Проверка кода · эвристика, не компилятор", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD4D4D4))
            Text(if (document.check.isEmpty()) "Явных проблем не найдено." else document.check.joinToString("\n") { "Строка ${it.line}: ${it.message}" },
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFD4D4D4))
            if (value.text.length > 60000) Text("Подсветка отключена для скорости на файлах свыше 60 000 символов.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9E9E9E))
        }
    }
    if (confirmSave) AlertDialog(onDismissRequest = { confirmSave = false }, title = { Text("Записать изменения?") },
        text = { Text("Содержимое «${document.entry.name}» будет заменено. Для SAF предварительно создаётся копия в разделе восстановления.") },
        confirmButton = { TextButton(onClick = { confirmSave = false; vm.save() }) { Text("Записать") } },
        dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Отмена") } })
}

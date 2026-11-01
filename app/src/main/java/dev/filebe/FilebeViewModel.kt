package dev.filebe

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

class FilebeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StorageRepository(application)
    private val ai = AiClient()
    private val preferences = application.getSharedPreferences("filebe", 0)
    private val mutable = MutableStateFlow(UiState(
        theme = runCatching {
            ThemeMode.valueOf(preferences.getString("theme", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(ThemeMode.SYSTEM),
        sites = parseSites(preferences.getString("sites", null))
    ))
    val state = mutable.asStateFlow()
    private var listing: Job? = null
    private var checking: Job? = null
    init { reloadGrants() }
    private fun reloadGrants() {
        val trees = getApplication<Application>().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }.map { Ref(it.uri.toString()) }
        mutable.update { it.copy(trees = trees) }
    }
    fun notify(message: String) { mutable.update { it.copy(message = message) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun tab(tab: Tab) { mutable.update { it.copy(tab = tab) } }
    fun theme(mode: ThemeMode) {
        preferences.edit().putString("theme", mode.name).apply()
        mutable.update { it.copy(theme = mode) }
    }
    /** Remembers the open page so the browser tab reopens on it; null returns it to the start page. */
    fun rememberBrowser(url: String?) { mutable.update { it.copy(browserUrl = url) } }
    fun addSite(site: Site) {
        if (state.value.sites.any { it.url == site.url } || BUILTIN_SITES.any { it.url == site.url }) { notify("Этот сайт уже в списке"); return }
        saveSites(state.value.sites + site)
        notify("Добавлено: ${site.title}")
    }
    fun removeSite(site: Site) { saveSites(state.value.sites.filter { it != site }) }
    private fun saveSites(sites: List<Site>) {
        preferences.edit().putString("sites", sites.joinToString("\n") { "${it.title}\t${it.url}" }).apply()
        mutable.update { it.copy(sites = sites) }
    }
    private fun parseSites(raw: String?): List<Site> = (raw ?: "").lines().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].startsWith("https://")) Site(parts[0], parts[1]) else null
    }
    fun grant(uri: Uri, flags: Int) {
        try {
            val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(granted and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Провайдер не разрешил чтение" }
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, granted)
            reloadGrants(); home(uri.toString())
        } catch (error: Exception) { notify("Не удалось сохранить доступ SAF: ${error.message}") }
    }
    fun home(path: String) {
        if (state.value.busy) return
        mutable.update { it.copy(folder = Ref(path), trail = emptyList(), query = "", entries = emptyList()) }
        refresh()
    }
    fun shared() { home(Environment.getExternalStorageDirectory().absolutePath) }
    fun enableRoot() = operation {
        repository.authorizeRoot()
        mutable.update { it.copy(folder = Ref("root:/"), trail = emptyList(), entries = emptyList(), query = "", message = "Root-доступ разрешён. Будьте осторожны с системными файлами.") }
    }
    fun appFiles() { home(getApplication<Application>().filesDir.absolutePath) }
    fun downloads() {
        val dir = File(Environment.getExternalStorageDirectory(), DOWNLOADS_SUBPATH).apply { mkdirs() }
        home(dir.absolutePath)
    }
    fun recovery() {
        val dir = File(getApplication<Application>().filesDir, "recovery").apply { mkdirs() }
        home(dir.absolutePath)
    }
    fun enter(entry: Entry) {
        if (state.value.busy) return
        if (!entry.directory) { open(entry); return }
        mutable.update { it.copy(folder = entry.ref, trail = it.trail + listOfNotNull(it.folder), query = "", entries = emptyList()) }
        refresh()
    }
    fun up(): Boolean {
        if (state.value.busy) return true
        val current = state.value
        if (current.trail.isNotEmpty()) {
            mutable.update { it.copy(folder = current.trail.last(), trail = current.trail.dropLast(1), query = "", entries = emptyList()) }
        } else if (current.folder != null) {
            mutable.update { it.copy(folder = null, query = "", entries = emptyList()) }
        } else return false
        refresh(); return true
    }
    fun query(query: String) { mutable.update { it.copy(query = query) }; refresh(true) }
    fun recursive(enabled: Boolean) { mutable.update { it.copy(recursive = enabled) }; refresh() }
    fun refresh(debounce: Boolean = false) {
        listing?.cancel()
        val snapshot = state.value
        val folder = snapshot.folder ?: return
        listing = viewModelScope.launch {
            if (debounce) delay(300)
            try {
                val entries = repository.list(folder, snapshot.query, snapshot.recursive)
                mutable.update { it.copy(entries = entries) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(entries = emptyList(), message = "Список не прочитан: ${error.message}") } }
        }
    }
    private fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        listing?.cancel()
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { notify(error.message ?: "Операция завершилась ошибкой") }
            finally { mutable.update { it.copy(busy = false) }; refresh() }
        }
    }
    fun copy(entry: Entry, move: Boolean) {
        mutable.update { it.copy(clipboard = ClipboardItem(entry, move), message = if (move) "Выберите папку и нажмите «Вставить и переместить»" else "Выберите папку и нажмите «Вставить»") }
    }
    fun cancelClipboard() { mutable.update { it.copy(clipboard = null) } }
    fun paste() {
        val item = state.value.clipboard ?: return
        val folder = state.value.folder ?: return
        operation {
            repository.paste(item, folder)
            mutable.update { it.copy(clipboard = null, message = "Готово") }
        }
    }
    fun make(name: String, directory: Boolean) {
        val folder = state.value.folder ?: return
        operation { repository.make(folder, name, directory); notify("Создано") }
    }
    fun rename(entry: Entry, name: String) = operation {
        check(state.value.entries.none { it.name == name && it.ref != entry.ref }) { "Имя уже занято" }
        val newRef = repository.rename(entry, name)
        mutable.update { current ->
            current.copy(opened = current.opened?.let {
                if (it.entry.ref == entry.ref) it.copy(entry = it.entry.copy(ref = newRef, name = name)) else it
            }, clipboard = null, message = "Переименовано")
        }
    }
    fun delete(entry: Entry) = operation {
        repository.delete(entry.ref)
        mutable.update { it.copy(opened = if (it.opened?.entry?.ref == entry.ref) null else it.opened,
            clipboard = null, message = "Удалено") }
    }
    fun zip(entry: Entry) {
        val folder = state.value.folder ?: return
        operation { repository.zip(entry, folder, "${entry.name}.zip"); notify("ZIP создан в текущей папке") }
    }
    fun open(entry: Entry) = operation {
        val opened = repository.open(entry)
        mutable.update { it.copy(opened = opened, tab = Tab.OPEN, proposal = null) }
        checkText()
    }
    fun edit(text: String) {
        if (state.value.busy) return
        mutable.update { current -> current.copy(opened = current.opened?.let { it.copy(text = text, revision = it.revision + 1) }) }
        checkText()
    }
    private fun checkText() {
        checking?.cancel()
        val snapshot = state.value.opened ?: return
        checking = viewModelScope.launch {
            delay(450)
            val diagnostics = withContext(Dispatchers.Default) { CodeTools.check(snapshot.text, snapshot.entry.name) }
            mutable.update { current -> current.copy(opened = current.opened?.let {
                if (it.entry.ref == snapshot.entry.ref && it.revision == snapshot.revision) it.copy(check = diagnostics) else it
            }) }
        }
    }
    fun save() {
        val snapshot = state.value.opened ?: return
        operation {
            val saved = repository.save(snapshot)
            mutable.update { it.copy(opened = saved, message = "Файл сохранён") }
        }
    }
    fun api(key: String, url: String, model: String) {
        mutable.update { it.copy(apiKey = key, apiUrl = url, apiModel = model) }
    }
    fun requestAi(prompt: String, includeFile: Boolean, reply: (String) -> Unit) {
        if (state.value.aiBusy || state.value.pendingAi != null) { reply("Уже выполняется запрос"); return }
        if (prompt.isBlank()) { reply("Введите запрос"); return }
        if (includeFile) {
            if (state.value.opened == null) { reply("Во вкладке «Открытый» нет файла"); return }
            mutable.update { it.copy(pendingAi = AiRequest(prompt, true, reply)) }
        } else runAi(AiRequest(prompt, false, reply))
    }
    fun confirmAi(accept: Boolean) {
        val request = state.value.pendingAi ?: return
        mutable.update { it.copy(pendingAi = null) }
        if (accept) runAi(request) else request.reply("Отправка отменена")
    }
    private fun runAi(request: AiRequest) {
        val snapshot = state.value
        val document = if (request.includeFile) snapshot.opened else null
        mutable.update { it.copy(aiBusy = true) }
        viewModelScope.launch {
            try {
                val answer = ai.ask(snapshot.apiUrl, snapshot.apiKey, snapshot.apiModel, request.prompt, document)
                val proposal = if (answer.replacement != null && document != null)
                    AiProposal(answer.replacement, document.entry.ref.id, document.revision) else null
                mutable.update { it.copy(proposal = proposal) }
                request.reply(answer.message + if (proposal != null) "\n\nПравка предложена. Просмотрите её и подтвердите в приложении." else "")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { request.reply("Ошибка: ${error.message ?: "не удалось выполнить запрос"}") }
            finally { mutable.update { it.copy(aiBusy = false) } }
        }
    }
    fun discardProposal() { mutable.update { it.copy(proposal = null) } }
    fun applyProposal() {
        val snapshot = state.value
        val proposal = snapshot.proposal ?: return
        val document = snapshot.opened
        if (document == null || document.entry.ref.id != proposal.documentId || document.revision != proposal.revision) {
            notify("Файл изменился после запроса. Правка не применена; отправьте новый запрос.")
            discardProposal(); return
        }
        edit(proposal.text)
        mutable.update { it.copy(proposal = null, tab = Tab.OPEN, message = "Правка применена в редакторе. Для записи на диск нажмите «Сохранить»." ) }
    }
}

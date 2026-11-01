package dev.filebe

/** A local absolute path or a persisted SAF tree/document URI. */
data class Ref(val id: String) {
    val saf get() = id.startsWith("content://")
    val root get() = id.startsWith("root:/")
}
data class Entry(val ref: Ref, val name: String, val directory: Boolean, val size: Long, val modified: Long)
data class OpenDocument(
    val entry: Entry,
    val text: String,
    val savedText: String,
    val originalHash: String,
    val revision: Long = 0,
    val check: List<Diagnostic> = emptyList()
) { val dirty get() = text != savedText }
data class Diagnostic(val line: Int, val message: String)
data class ClipboardItem(val entry: Entry, val move: Boolean)
data class AiProposal(val text: String, val documentId: String, val revision: Long)
data class AiRequest(val prompt: String, val includeFile: Boolean, val reply: (String) -> Unit)
enum class Tab(val title: String) { FILES("Файлы"), OPEN("Открытый"), BROWSER("Браузер") }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
data class UiState(
    val tab: Tab = Tab.FILES,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val folder: Ref? = null,
    val trail: List<Ref> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val trees: List<Ref> = emptyList(),
    val query: String = "",
    val recursive: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val opened: OpenDocument? = null,
    val clipboard: ClipboardItem? = null,
    val proposal: AiProposal? = null,
    val pendingAi: AiRequest? = null,
    val aiBusy: Boolean = false,
    val apiKey: String = "",
    val apiUrl: String = "https://api.openai.com/v1/chat/completions",
    val apiModel: String = "gpt-4o-mini",
    /** Page open in the browser tab; null means the start page with the site list. */
    val browserUrl: String? = null,
    /** Sites added by the user to the browser start page. */
    val sites: List<Site> = emptyList()
)

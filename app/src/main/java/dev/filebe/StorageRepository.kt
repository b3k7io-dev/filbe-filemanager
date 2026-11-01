package dev.filebe

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageRepository(private val context: Context) {
    companion object { const val MAX_TEXT_BYTES = 1024 * 1024 }
    private val resolver get() = context.contentResolver
    private val root = RootAccess(context)
    suspend fun authorizeRoot() = withContext(Dispatchers.IO) { root.authorize() }
    private fun identity(ref: Ref): String = if (ref.root) root.canonical(ref) else if (ref.saf) ref.id else File(ref.id).canonicalPath

    private fun doc(ref: Ref): DocumentFile {
        val uri = Uri.parse(ref.id)
        return (if (uri.path.orEmpty().contains("/document/")) DocumentFile.fromSingleUri(context, uri)
        else DocumentFile.fromTreeUri(context, uri)) ?: error("Недоступный URI")
    }
    fun info(ref: Ref): Entry = if (ref.root) root.info(ref) else if (ref.saf) {
        val d = doc(ref)
        check(d.exists()) { "Документ удалён или доступ отозван" }
        Entry(ref, d.name ?: "Без имени", d.isDirectory, d.length(), d.lastModified())
    } else {
        val f = File(ref.id)
        check(f.exists()) { "Путь не существует или доступ запрещён: ${ref.id}" }
        Entry(ref, f.name.ifEmpty { "/" }, f.isDirectory, f.length(), f.lastModified())
    }
    private fun children(ref: Ref): List<Entry> {
        if (ref.root) return root.children(ref)
        if (!ref.saf) {
            val files = File(ref.id).listFiles() ?: error("Нет разрешения на чтение ${ref.id}")
            return files.map { Entry(Ref(it.absolutePath), it.name, it.isDirectory, it.length(), it.lastModified()) }
        }
        // SingleDocumentFile does not implement listFiles. Query a tree-granted document explicitly.
        val parent = Uri.parse(ref.id)
        val parentId = if (parent.path.orEmpty().contains("/document/")) DocumentsContract.getDocumentId(parent)
            else DocumentsContract.getTreeDocumentId(parent)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val result = mutableListOf<Entry>()
        resolver.query(childUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                result += Entry(Ref(uri.toString()), cursor.getString(1) ?: "Без имени",
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    cursor.getLong(3), cursor.getLong(4))
            }
        } ?: error("Провайдер не вернул список документов")
        return result
    }
    suspend fun list(ref: Ref, query: String, recursive: Boolean): List<Entry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Entry>()
        val seen = hashSetOf<String>()
        var visited = 0
        fun scan(folder: Ref, depth: Int) {
            check(depth <= 64 && visited < 100000) { "Поиск превысил лимит глубины/100000 записей. Уточните папку." }
            val identity = identity(folder)
            if (!seen.add(identity)) return
            for (entry in children(folder)) {
                visited++
                if (query.isBlank() || entry.name.contains(query, ignoreCase = true)) result += entry
                if (recursive && query.isNotBlank() && entry.directory && !isSymlink(entry.ref)) {
                    // Permission failures are surfaced rather than claiming complete search coverage.
                    scan(entry.ref, depth + 1)
                }
            }
        }
        scan(ref, 0)
        result.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
    }
    private fun isSymlink(ref: Ref): Boolean = if (ref.root) root.isSymlink(ref) else !ref.saf && java.nio.file.Files.isSymbolicLink(File(ref.id).toPath())
    private fun read(ref: Ref): InputStream = if (ref.root) root.read(ref) else if (ref.saf) resolver.openInputStream(Uri.parse(ref.id))
        ?: error("Нельзя открыть документ") else File(ref.id).inputStream()
    private fun write(ref: Ref): OutputStream = if (ref.root) root.write(ref) else if (ref.saf) resolver.openOutputStream(Uri.parse(ref.id), "wt")
        ?: error("Нельзя записать документ") else File(ref.id).outputStream()
    private fun validName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Недопустимое имя" }
        require(name.toByteArray().size <= 240) { "Слишком длинное имя" }
    }
    private fun create(parent: Ref, name: String, directory: Boolean, mime: String = "application/octet-stream"): Ref {
        validName(name)
        check(children(parent).none { it.name == name }) { "Объект с таким именем уже существует" }
        return if (parent.root) root.create(parent, name, directory) else if (parent.saf) {
            val uri = Uri.parse(parent.id)
            val parentUri = if (uri.path.orEmpty().contains("/document/")) uri
                else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            Ref((DocumentsContract.createDocument(resolver, parentUri,
                if (directory) DocumentsContract.Document.MIME_TYPE_DIR else mime, name)
                ?: error("Не удалось создать документ")).toString())
        } else {
            val child = File(parent.id, name)
            check(if (directory) child.mkdir() else child.createNewFile()) { "Не удалось создать $name" }
            Ref(child.absolutePath)
        }
    }
    suspend fun make(parent: Ref, name: String, directory: Boolean) = withContext(Dispatchers.IO) {
        create(parent, name, directory)
    }
    suspend fun rename(entry: Entry, name: String): Ref = withContext(Dispatchers.IO) {
        validName(name)
        check(entry.ref.id != "/") { "Корень переименовать нельзя" }
        if (entry.ref.root) root.rename(entry.ref, name) else if (entry.ref.saf) {
            val uri = Uri.parse(entry.ref.id)
            Ref((DocumentsContract.renameDocument(resolver, uri, name)
                ?: error("Провайдер отказал в переименовании")).toString())
        } else {
            val old = File(entry.ref.id)
            val destination = File(old.parentFile ?: error("Нет родителя"), name)
            check(!destination.exists()) { "Такое имя уже существует" }
            check(old.renameTo(destination)) { "Переименование не удалось" }
            Ref(destination.absolutePath)
        }
    }
    private fun deleteTree(ref: Ref, depth: Int = 0) {
        check(depth < 128) { "Слишком большая глубина" }
        check(ref.id != "/") { "Удаление корня запрещено" }
        if (ref.root) root.delete(ref)
        else if (ref.saf) check(doc(ref).delete()) { "Не удалось удалить документ" }
        else {
            val file = File(ref.id)
            if (file.isDirectory && !isSymlink(ref)) children(ref).forEach { deleteTree(it.ref, depth + 1) }
            check(file.delete()) { "Не удалось удалить ${ref.id}" }
        }
    }
    suspend fun delete(ref: Ref) = withContext(Dispatchers.IO) { deleteTree(ref) }
    private fun assertDestination(source: Entry, destination: Ref) {
        require(source.ref != destination) { "Источник совпадает с назначением" }
        if (!source.ref.saf && !destination.saf) {
            val useRoot = source.ref.root || destination.root
            val from = File(if (useRoot) root.canonical(source.ref) else identity(source.ref)).toPath()
            val to = File(if (useRoot) root.canonical(destination) else identity(destination)).toPath()
            require(!to.startsWith(from)) { "Нельзя копировать папку внутрь себя" }
        } else if (source.ref.saf && destination.saf && source.directory) {
            val a = Uri.parse(source.ref.id)
            val b = Uri.parse(destination.id)
            if (a.authority == b.authority) {
                val bId = if (b.path.orEmpty().contains("/document/")) DocumentsContract.getDocumentId(b)
                    else DocumentsContract.getTreeDocumentId(b)
                val aId = if (a.path.orEmpty().contains("/document/")) DocumentsContract.getDocumentId(a)
                    else DocumentsContract.getTreeDocumentId(a)
                val bIsChildOfA = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val aDoc = DocumentsContract.buildDocumentUriUsingTree(a, aId)
                    val bDoc = DocumentsContract.buildDocumentUriUsingTree(b, bId)
                    DocumentsContract.isChildDocument(resolver, aDoc, bDoc)
                } else {
                    // isChildDocument needs API 29. Below that we fall back to a hierarchical
                    // id-prefix check, which covers the common SAF providers whose document
                    // ids are "root:relative/path" (external storage, most file managers).
                    val aPath = aId.substringAfter(':', aId)
                    val bPath = bId.substringAfter(':', bId)
                    bPath == aPath || bPath.startsWith("$aPath/")
                }
                check(aId != bId && !bIsChildOfA) { "Папка назначения совпадает с источником или находится внутри него" }
            }
        }
        // Different namespaces can alias the same storage. Without a reliable identity, refuse a directory cross-copy.
        require(!source.directory || source.ref.saf == destination.saf) {
            "Для папок используйте один режим доступа (SAF или путь); файлы можно переносить между режимами."
        }
    }
    suspend fun paste(item: ClipboardItem, destination: Ref) = withContext(Dispatchers.IO) {
        assertDestination(item.entry, destination)
        check(children(destination).none { it.name == item.entry.name }) { "Имя занято; переименуйте источник или выберите другую папку" }
        val stagingName = ".filebe-${UUID.randomUUID()}"
        var staging: Ref? = null
        fun copy(source: Entry, target: Ref, depth: Int) {
            check(depth < 64) { "Слишком глубокое дерево" }
            check(!isSymlink(source.ref)) { "Копирование символических ссылок не поддерживается" }
            if (source.directory) children(source.ref).forEach {
                val child = create(target, it.name, it.directory)
                copy(it, child, depth + 1)
            } else read(source.ref).use { input -> write(target).use { output -> input.copyTo(output) } }
        }
        try {
            staging = create(destination, stagingName, item.entry.directory)
            copy(item.entry, staging, 0)
            rename(info(staging), item.entry.name)
            staging = null
        } catch (error: Exception) {
            staging?.let { pending -> runCatching { deleteTree(pending) }.exceptionOrNull()?.let { error.addSuppressed(it) } }
            throw error
        }
        // Source is removed only after the destination has been fully copied and finalized.
        if (item.move) deleteTree(item.entry.ref)
    }
    suspend fun zip(entry: Entry, destination: Ref, name: String) = withContext(Dispatchers.IO) {
        assertDestination(entry, destination)
        var target: Ref? = null
        val seen = hashSetOf<String>()
        var count = 0
        try {
            target = create(destination, name, false, "application/zip")
            write(target).use { output -> ZipOutputStream(output).use { zip ->
                fun append(node: Entry, prefix: String, depth: Int) {
                    check(depth < 64 && ++count <= 100000) { "Превышен лимит ZIP" }
                    validName(node.name)
                    check(!isSymlink(node.ref)) { "ZIP символических ссылок не поддерживается" }
                    val identity = identity(node.ref)
                    check(seen.add(identity)) { "Цикл в дереве файлов" }
                    val path = prefix + node.name + if (node.directory) "/" else ""
                    zip.putNextEntry(ZipEntry(path))
                    if (!node.directory) read(node.ref).use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (node.directory) children(node.ref).forEach { append(it, path, depth + 1) }
                }
                append(entry, "", 0)
            } }
            target
        } catch (error: Exception) {
            target?.let { runCatching { deleteTree(it) }.exceptionOrNull()?.let(error::addSuppressed) }
            throw error
        }
    }
    private fun bytes(ref: Ref): ByteArray = read(ref).use { stream ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            require(buffer.size() + count <= MAX_TEXT_BYTES) { "Редактор поддерживает UTF-8 файлы до 1 МиБ" }
            buffer.write(chunk, 0, count)
        }
        val bytes = buffer.toByteArray()
        require(bytes.none { it == 0.toByte() }) { "Бинарный файл: используйте свойства или внешний просмотрщик" }
        bytes
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun open(entry: Entry): OpenDocument = withContext(Dispatchers.IO) {
        check(!entry.directory)
        val raw = bytes(entry.ref)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        OpenDocument(entry, text, text, hash(raw))
    }
    suspend fun save(document: OpenDocument): OpenDocument = withContext(Dispatchers.IO) {
        val raw = document.text.toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_TEXT_BYTES) { "Текст больше 1 МиБ" }
        check(hash(bytes(document.entry.ref)) == document.originalHash) { "Файл изменён другим приложением. Откройте заново; сохранение отменено." }
        if (document.entry.ref.root) {
            root.saveAtomic(document.entry.ref, raw)
        } else if (document.entry.ref.saf) {
            // SAF has no portable atomic replace. Keep an internal recovery copy BEFORE truncation.
            val recovery = File(context.filesDir, "recovery").apply { mkdirs() }
            val stem = System.currentTimeMillis().toString() + "-" + UUID.randomUUID()
            File(recovery, "$stem.original").writeBytes(bytes(document.entry.ref))
            File(recovery, "$stem.edited").writeBytes(raw)
            File(recovery, "$stem.path").writeText(document.entry.ref.id)
            write(document.entry.ref).use { it.write(raw) }
        } else {
            val file = File(document.entry.ref.id)
            val temporary = File(file.parentFile, ".filebe-save-${UUID.randomUUID()}")
            try {
                temporary.outputStream().use { it.write(raw); it.fd.sync() }
                check(temporary.renameTo(file)) { "Атомарное сохранение не удалось; исходник не заменён" }
            } finally { if (temporary.exists()) check(temporary.delete()) { "Не удалён временный файл ${temporary.path}" } }
        }
        document.copy(entry = info(document.entry.ref), savedText = document.text, originalHash = hash(raw))
    }
    suspend fun recoveryFiles(): List<Entry> = withContext(Dispatchers.IO) {
        val folder = File(context.filesDir, "recovery").apply { mkdirs() }
        children(Ref(folder.absolutePath))
    }
}

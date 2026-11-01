package dev.filebe

import android.content.Context
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicitly enabled, optional su backend. No command from a WebView is ever executed. */
class RootAccess(private val context: Context) {
    @Volatile private var enabled = false
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun path(ref: Ref): String {
        require(ref.root) { "Ожидался root-путь" }
        val path = ref.id.removePrefix("root:")
        require(path.startsWith('/') && '\u0000' !in path) { "Недопустимый root-путь" }
        return path
    }
    private data class Execution(val process: Process, val errors: File)
    private fun start(command: String, output: File? = null, authorizing: Boolean = false): Execution {
        check(enabled || authorizing) { "Сначала явно разрешите root-доступ" }
        val errors = File.createTempFile("root-error-", ".log", context.cacheDir)
        try {
            val builder = ProcessBuilder("su", "-c", command).redirectError(errors)
            if (output != null) builder.redirectOutput(output)
            return Execution(builder.start(), errors)
        } catch (error: Exception) {
            errors.delete()
            throw IllegalStateException("Не удалось запустить su. Требуется рутированное устройство и менеджер root-доступа.", error)
        }
    }
    private fun finish(execution: Execution) {
        try {
            if (!execution.process.waitFor(60, TimeUnit.SECONDS)) {
                execution.process.destroyForcibly()
                error("Истекло время root-команды; проверьте запрос в менеджере root")
            }
            val status = execution.process.exitValue()
            check(status == 0) { "Root-команда завершилась с кодом $status: ${execution.errors.inputStream().use { String(it.readBytes().take(2048).toByteArray()) }}" }
        } finally { execution.errors.delete() }
    }
    private fun run(command: String, authorizing: Boolean = false): ByteArray {
        val output = File.createTempFile("root-out-", ".bin", context.cacheDir)
        try {
            val execution = start(command, output, authorizing)
            execution.process.outputStream.close()
            finish(execution)
            check(output.length() <= 4 * 1024 * 1024) { "Ответ root-команды больше 4 МиБ; выберите меньшую папку" }
            return output.readBytes()
        } finally { output.delete() }
    }
    fun authorize() {
        val uid = String(run("id -u", authorizing = true), Charsets.UTF_8).trim()
        check(uid == "0") { "Менеджер root не предоставил UID 0" }
        enabled = true
    }
    fun canonical(ref: Ref): String {
        val target = if (ref.root) path(ref) else ref.id
        return String(run("readlink -f -- ${quote(target)}"), Charsets.UTF_8).trimEnd('\n', '\r')
            .also { check(it.startsWith('/')) { "Не удалось разрешить путь" } }
    }
    fun isSymlink(ref: Ref) = String(run("if [ -L ${quote(path(ref))} ]; then printf yes; else printf no; fi")) == "yes"
    fun info(ref: Ref): Entry {
        val p = path(ref)
        val output = String(run("set -e; p=${quote(p)}; [ -e \"\u0024p\" ] || [ -L \"\u0024p\" ]; if [ -d \"\u0024p\" ] && [ ! -L \"\u0024p\" ]; then printf 'd '; else printf 'f '; fi; stat -c '%s %Y' -- \"\u0024p\""))
        val values = output.trim().split(' ')
        require(values.size == 3) { "Неподдерживаемый формат stat" }
        return Entry(ref, File(p).name.ifEmpty { "/" }, values[0] == "d", values[1].toLong(), values[2].toLong() * 1000)
    }
    fun children(ref: Ref): List<Entry> {
        val command = """
            set -e
            base=${quote(path(ref))}
            [ -d "\u0024base" ]
            [ -r "\u0024base" ]
            for p in "\u0024base"/* "\u0024base"/.[!.]* "\u0024base"/..?*; do
              [ -e "\u0024p" ] || [ -L "\u0024p" ] || continue
              kind=f
              if [ -d "\u0024p" ] && [ ! -L "\u0024p" ]; then kind=d; fi
              meta=\u0024(stat -c '%s %Y' -- "\u0024p")
              printf '%s\000%s\000%s\000' "\u0024p" "\u0024kind" "\u0024meta"
            done
        """.trimIndent().replace("\\u0024", "\u0024")
        val parts = String(run(command), Charsets.UTF_8).split('\u0000').dropLast(1)
        check(parts.size % 3 == 0) { "Неподдерживаемый формат root-листинга" }
        return parts.chunked(3).map { (p, kind, meta) ->
            val values = meta.trim().split(' ')
            Entry(Ref("root:$p"), File(p).name, kind == "d", values[0].toLong(), values[1].toLong() * 1000)
        }
    }
    private fun regularPath(ref: Ref, writing: Boolean = false): String {
        val resolved = canonical(ref)
        require(listOf("/proc", "/sys", "/dev").none { resolved == it || resolved.startsWith("$it/") }) {
            "Чтение/изменение виртуальных файлов /proc, /sys и /dev не поддерживается"
        }
        if (writing) require(resolved != "/") { "Изменение корня запрещено" }
        return resolved
    }
    fun read(ref: Ref): InputStream {
        regularPath(ref)
        val execution = start("[ -f ${quote(path(ref))} ] && exec cat -- ${quote(path(ref))}")
        execution.process.outputStream.close()
        return object : FilterInputStream(execution.process.inputStream) {
            override fun close() {
                try { super.close() } finally { finish(execution) }
            }
        }
    }
    fun write(ref: Ref): OutputStream {
        regularPath(ref, writing = true)
        val execution = start("[ -f ${quote(path(ref))} ] && cat > ${quote(path(ref))}", File("/dev/null"))
        return object : FilterOutputStream(execution.process.outputStream) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() {
                try { super.close() } finally { finish(execution) }
            }
        }
    }
    fun create(parent: Ref, name: String, directory: Boolean): Ref {
        regularPath(parent)
        val destination = File(path(parent), name).path
        val q = quote(destination)
        run("set -e; [ ! -e $q ] && [ ! -L $q ]; " + if (directory) "mkdir -- $q" else "set -C; : > $q")
        return Ref("root:$destination")
    }
    fun rename(ref: Ref, name: String): Ref {
        val from = path(ref)
        check(regularPath(ref, writing = true) != "/") { "Нельзя переименовать корень" }
        val destination = File(File(from).parentFile ?: error("Нет родителя"), name).path
        val q = quote(destination)
        run("set -e; [ ! -e $q ] && [ ! -L $q ]; mv -- ${quote(from)} $q")
        return Ref("root:$destination")
    }
    fun delete(ref: Ref) {
        regularPath(ref, writing = true)
        run("rm -rf -- ${quote(path(ref))}")
    }
    fun saveAtomic(ref: Ref, bytes: ByteArray) {
        val destination = path(ref)
        check(!isSymlink(ref)) { "Сохранение поверх root-символической ссылки запрещено" }
        val temporary = File(File(destination).parentFile, ".filebe-${UUID.randomUUID()}").path
        val tempRef = Ref("root:$temporary")
        var created = false
        try {
            val parent = Ref("root:" + (File(destination).parent ?: error("Нет родителя")))
            create(parent, File(temporary).name, false)
            created = true
            write(tempRef).use { it.write(bytes) }
            val metadata = String(run("stat -c '%a %u %g' -- ${quote(destination)}")).trim().split(' ')
            check(metadata.size == 3 && metadata.all { it.matches(Regex("[0-9]+")) }) { "Не удалось прочитать права исходника" }
            run("set -e; chmod ${metadata[0]} ${quote(temporary)}; chown ${metadata[1]}:${metadata[2]} ${quote(temporary)}; mv -f -- ${quote(temporary)} ${quote(destination)}")
            created = false
        } finally { if (created) delete(tempRef) }
    }
}

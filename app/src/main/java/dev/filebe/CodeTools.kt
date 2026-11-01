package dev.filebe

object CodeTools {
    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD }
    data class Token(val start: Int, val end: Int, val kind: Kind)
    private val keywords = setOf("fun", "val", "var", "class", "object", "interface", "package", "import", "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "true", "false", "null", "public", "private", "protected", "internal", "override", "suspend", "data", "sealed", "enum", "const", "let", "function", "async", "await", "export", "default", "new", "this", "extends", "implements", "static", "void", "int", "boolean", "def", "from", "as", "in", "is", "None", "True", "False", "with", "lambda", "pass", "raise", "yield")
    fun tokens(text: String, filename: String): List<Token> {
        val python = filename.endsWith(".py", true)
        val result = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            val c = text[i]
            when {
                text.startsWith("//", i) || (python && c == '#') -> {
                    while (i < text.length && text[i] != '\n') i++
                    result += Token(start, i, Kind.COMMENT)
                }
                text.startsWith("/*", i) -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    result += Token(start, i, Kind.COMMENT)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val triple = c != '`' && text.startsWith(c.toString().repeat(3), i)
                    val endMarker = c.toString().repeat(if (triple) 3 else 1)
                    i += endMarker.length
                    while (i < text.length) {
                        if (text[i] == '\\') { i = (i + 2).coerceAtMost(text.length); continue }
                        if (text.startsWith(endMarker, i)) { i += endMarker.length; break }
                        i++
                    }
                    result += Token(start, i, Kind.STRING)
                }
                c.isDigit() -> {
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "._")) i++
                    result += Token(start, i, Kind.NUMBER)
                }
                c.isLetter() || c == '_' -> {
                    i++
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    if (text.substring(start, i) in keywords) result += Token(start, i, Kind.KEYWORD)
                }
                else -> i++
            }
        }
        return result
    }
    /** Fast local heuristic, NOT a compiler or a security audit. */
    fun check(text: String, filename: String): List<Diagnostic> {
        val masked = text.toCharArray()
        tokens(text, filename).filter { it.kind == Kind.STRING || it.kind == Kind.COMMENT }.forEach {
            for (i in it.start until it.end) if (masked[i] != '\n') masked[i] = ' '
        }
        val result = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Pair<Char, Int>>()
        var line = 1
        for (c in masked) {
            if (c == '\n') line++
            if (c in "({[") stack.addLast(c to line)
            if (c in ")}]" ) {
                val expected = when (c) { ')' -> '('; '}' -> '{'; else -> '[' }
                if (stack.lastOrNull()?.first == expected) stack.removeLast()
                else result += Diagnostic(line, "Неожиданная закрывающая скобка $c")
            }
        }
        stack.forEach { result += Diagnostic(it.second, "Не закрыта скобка ${it.first}") }
        text.lineSequence().forEachIndexed { index, value ->
            if (value.endsWith(' ') || value.endsWith('\t')) result += Diagnostic(index + 1, "Пробелы в конце строки")
            if (value.length > 180) result += Diagnostic(index + 1, "Строка длиннее 180 символов")
        }
        return result.sortedBy { it.line }.take(200)
    }
}

package dev.filebe

import org.junit.Assert.*
import org.junit.Test

class CodeToolsTest {
    @Test fun ignoresBracesInsideCommentsAndStrings() {
        val source = "fun main() { val s = \"}\" // ]\n/* ( */ }"
        assertTrue(CodeTools.check(source, "main.kt").isEmpty())
    }
    @Test fun findsUnclosedBrace() {
        assertTrue(CodeTools.check("fun x() {\n", "x.kt").any { it.line == 1 && it.message.contains("Не закрыта") })
    }
    @Test fun pythonHashComment() {
        assertTrue(CodeTools.check("# (\nx = '}'", "x.py").isEmpty())
    }
    @Test fun identifiesKeywordsAndEscapedStrings() {
        val source = "val s = \"a\\\"b\""
        val tokens = CodeTools.tokens(source, "x.kt")
        assertEquals(CodeTools.Kind.KEYWORD, tokens.first().kind)
        assertEquals(source.length, tokens.last().end)
    }
    @Test fun reportsTrailingWhitespace() {
        assertTrue(CodeTools.check("x = 1 \n", "x.py").any { it.line == 1 })
    }
    @Test fun supportsEmptyFile() { assertTrue(CodeTools.check("", "empty.txt").isEmpty()) }
    @Test fun tripleQuotedStrings() {
        assertTrue(CodeTools.check("x = \"\"\"{\n[\n\"\"\"", "x.py").isEmpty())
    }
}

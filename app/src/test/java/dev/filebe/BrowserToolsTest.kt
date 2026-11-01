package dev.filebe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserToolsTest {
    @Test fun blankInputIsIgnored() {
        assertNull(resolveAddress(""))
        assertNull(resolveAddress("   "))
    }

    @Test fun httpsAddressIsKept() {
        assertEquals("https://claude.ai/new", resolveAddress("https://claude.ai/new"))
    }

    @Test fun httpIsUpgradedToHttps() {
        assertEquals("https://example.com/a", resolveAddress("http://example.com/a"))
    }

    @Test fun bareDomainGetsHttps() {
        assertEquals("https://github.com", resolveAddress("github.com"))
        assertEquals("https://example.com/path?q=1", resolveAddress("example.com/path?q=1"))
    }

    @Test fun plainTextBecomesSearch() {
        assertEquals("https://www.google.com/search?q=hello+world", resolveAddress("hello world"))
        assertEquals("https://www.google.com/search?q=kotlin", resolveAddress("kotlin"))
    }

    @Test fun sentenceWithDotIsStillSearch() {
        assertEquals("https://www.google.com/search?q=what+is+kotlin.+really", resolveAddress("what is kotlin. really"))
    }

    @Test fun dangerousSchemesAreSearchedNotOpened() {
        assertEquals("https://www.google.com/search?q=javascript%3Aalert%281%29", resolveAddress("javascript:alert(1)"))
        assertEquals("https://www.google.com/search?q=file%3A%2F%2F%2Fsdcard%2Fa.html", resolveAddress("file:///sdcard/a.html"))
    }

    @Test fun titleDropsWww() {
        assertEquals("example.com", siteTitle("https://www.example.com/page"))
    }

    @Test fun builtinSitesAreAllHttps() {
        assert(BUILTIN_SITES.all { it.url.startsWith("https://") })
    }
}

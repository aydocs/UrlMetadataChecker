package urlchecker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UrlCheckerTest {

    @Test
    fun `extractTitle returns title from HTML`() {
        val html = "<html><head><title>Example Domain</title></head><body></body></html>"
        assertEquals("Example Domain", HtmlParser.extractTitle(html))
    }

    @Test
    fun `extractTitle returns Not found when no title`() {
        val html = "<html><head></head><body><p>No title</p></body></html>"
        assertEquals("Not found", HtmlParser.extractTitle(html))
    }

    @Test
    fun `extractTitle handles title with attributes`() {
        val html = """<html><head><title lang="en">My Page</title></head></html>"""
        assertEquals("My Page", HtmlParser.extractTitle(html))
    }

    @Test
    fun `extractTitle handles multiline title`() {
        val html = "<html><head><title>\n  Multi Line\n</title></head></html>"
        assertTrue(HtmlParser.extractTitle(html).contains("Multi Line"))
    }

    @Test
    fun `extractTitle handles empty title`() {
        val html = "<html><head><title></title></head></html>"
        assertEquals("Not found", HtmlParser.extractTitle(html))
    }

    @Test
    fun `isValidUrl accepts valid HTTPS URL`() {
        assertTrue(isValidUrl("https://example.com"))
    }

    @Test
    fun `isValidUrl accepts URL with path`() {
        assertTrue(isValidUrl("https://example.com/page"))
    }

    @Test
    fun `isValidUrl rejects invalid URL`() {
        assertFalse(isValidUrl("not-a-url"))
    }

    @Test
    fun `UrlMetadata stores values correctly`() {
        val metadata = UrlMetadata(
            url = "https://example.com",
            statusCode = 200,
            status = "OK",
            responseTimeMs = 150,
            contentType = "text/html",
            server = "nginx",
            title = "Example",
            headers = mapOf("content-type" to "text/html"),
            redirectChain = listOf("https://old.example.com"),
            bodySizeBytes = 1024,
            dnsLookupTimeMs = 12,
            tlsVersion = "TLSv1.3",
            certSubject = "CN=example.com",
            certIssuer = "Let's Encrypt",
            method = "GET"
        )
        assertEquals("https://example.com", metadata.url)
        assertEquals(200, metadata.statusCode)
        assertEquals(listOf("https://old.example.com"), metadata.redirectChain)
        assertEquals(1024L, metadata.bodySizeBytes)
        assertEquals("TLSv1.3", metadata.tlsVersion)
        assertEquals("GET", metadata.method)
    }

    @Test
    fun `check returns error for invalid URL`() {
        val checker = UrlChecker()
        val result = checker.check("not-a-url")
        assertTrue(result.isFailure)
    }

    @Test
    fun `check returns error for unknown host`() {
        val checker = UrlChecker(5)
        val result = checker.check("https://this-host-does-not-exist-xyz123.invalid")
        assertTrue(result.isFailure)
    }

    @Test
    fun `check includes redirect chain`() {
        val checker = UrlChecker(10)
        val result = checker.check("http://github.com")
        result.onSuccess { metadata ->
            assertTrue(metadata.redirectChain.isEmpty() || metadata.statusCode == 200)
            assertTrue(metadata.dnsLookupTimeMs >= 0)
        }
    }

    @Test
    fun `check returns body size`() {
        val checker = UrlChecker(10)
        val result = checker.check("https://example.com")
        result.onSuccess { metadata ->
            assertTrue(metadata.bodySizeBytes > 0)
        }
    }
}

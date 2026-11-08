package urlchecker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class UrlCheckerTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "<html><head><title>Local Test</title></head><body>ok</body></html>"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Server", "UrlCheckerTest")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

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
    fun `isValidUrl rejects unsupported schemes`() {
        assertFalse(isValidUrl("ftp://example.com/file.txt"))
        assertFalse(isValidUrl("file:///tmp/file.txt"))
    }

    @Test
    fun `isValidUrl rejects credentials and fragments`() {
        assertFalse(isValidUrl("https://user:password@example.com"))
        assertFalse(isValidUrl("https://example.com/page#private"))
    }

    @Test
    fun `json output escapes control characters`() {
        val metadata = UrlMetadata(
            url = "https://example.com",
            statusCode = 200,
            status = "OK",
            responseTimeMs = 1,
            contentType = "text/plain",
            server = "test",
            title = "line1\nline2\"quoted\"",
            headers = emptyMap()
        )
        val output = printJsonForTest(metadata)
        assertTrue(output.contains("line1\\nline2\\\"quoted\\\""))
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
    fun `checker rejects non-positive timeout`() {
        assertThrows(IllegalArgumentException::class.java) { UrlChecker(0) }
    }

    @Test
    fun `checker rejects timeout over one hour`() {
        assertThrows(IllegalArgumentException::class.java) { UrlChecker(3601) }
    }

    @Test
    fun `checker rejects unsupported method`() {
        assertTrue(UrlChecker().check(baseUrl, "OPTIONS").isFailure)
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
        val result = checker.check("$baseUrl/redirect")
        assertTrue(result.isSuccess)
        val metadata = result.getOrThrow()
        assertEquals(200, metadata.statusCode)
        assertEquals(listOf("$baseUrl/redirect"), metadata.redirectChain)
        assertTrue(metadata.dnsLookupTimeMs >= 0)
    }

    @Test
    fun `check returns body size`() {
        val checker = UrlChecker(10)
        val result = checker.check(baseUrl)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().bodySizeBytes > 0)
    }

    @Test
    fun `check reports UTF-8 body size in bytes`() {
        val unicodeBody = "çalışıyor 🚀"
        server.removeContext("/")
        server.createContext("/") { exchange ->
            val body = unicodeBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val result = UrlChecker().check(baseUrl)
        assertTrue(result.isSuccess)
        assertEquals(unicodeBody.toByteArray(StandardCharsets.UTF_8).size.toLong(), result.getOrThrow().bodySizeBytes)
    }
}

package urlchecker

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import java.io.InputStream
import java.util.Locale
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

class UrlChecker(
    private val timeoutSeconds: Long = 30,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES
) {

    init {
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) {
            "Timeout must be between 1 and $MAX_TIMEOUT_SECONDS seconds"
        }
        require(maxResponseBytes in 1 until Int.MAX_VALUE) { "Maximum response size must be positive" }
    }

    fun check(url: String, method: String = "GET", requestBody: String? = null): Result<UrlMetadata> {
        return runCatching {
            val startDns = System.nanoTime()
            val parsedUrl = URI.create(url)
            require(parsedUrl.scheme.lowercase() in setOf("http", "https") && !parsedUrl.host.isNullOrBlank()) {
                "Invalid URL."
            }
            require(parsedUrl.userInfo == null && parsedUrl.fragment == null) {
                "URL must not contain credentials or a fragment."
            }
            val normalizedMethod = method.uppercase(Locale.ROOT)
            require(normalizedMethod in SUPPORTED_METHODS) {
                "Unsupported method '$method'. Supported: ${SUPPORTED_METHODS.joinToString(", ")}"
            }
            val host = parsedUrl.host
            InetAddress.getByName(host)
            val dnsTime = (System.nanoTime() - startDns) / 1_000_000

            val sslInfo = if (parsedUrl.scheme.equals("https", ignoreCase = true)) {
                fetchSslInfo(host, parsedUrl.port)
            } else null

            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build()

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "UrlChecker/1.0")

            val request = when (normalizedMethod) {
                "POST" -> {
                    builder.POST(HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                }
                "PUT" -> {
                    builder.PUT(HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                }
                "DELETE" -> {
                    builder.DELETE()
                }
                "HEAD" -> {
                    builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                }
                "PATCH" -> {
                    builder.method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                }
                else -> builder.GET()
            }.build()

            val startTime = System.nanoTime()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            val redirectChain = mutableListOf<String>()
            var currentResponse = response
            var currentUrl = url
            var statusCode = response.statusCode()

            var redirectCount = 0
            while (statusCode in setOf(301, 302, 303, 307, 308) && redirectCount < MAX_REDIRECTS) {
                val location = currentResponse.headers().firstValue("Location").orElse(null)
                if (location == null) break
                redirectChain.add(currentResponse.request().uri().toString())
                val resolvedUrl = URI.create(currentUrl).resolve(location)
                require(resolvedUrl.scheme.lowercase() in setOf("http", "https") && !resolvedUrl.host.isNullOrBlank()) {
                    "Invalid redirect URL."
                }
                require(resolvedUrl.userInfo == null && resolvedUrl.fragment == null) {
                    "Redirect URL must not contain credentials or a fragment."
                }
                currentUrl = resolvedUrl.toString()
                currentResponse.body().close()
                val redirectMethod = if (statusCode == 307 || statusCode == 308) normalizedMethod else "GET"
                val nextBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "UrlChecker/1.0")
                val nextRequest = when (redirectMethod) {
                    "POST" -> nextBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                    "PUT" -> nextBuilder.PUT(HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                    "DELETE" -> nextBuilder.DELETE()
                    "HEAD" -> nextBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                    "PATCH" -> nextBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody ?: ""))
                    else -> nextBuilder.GET()
                }.build()
                currentResponse = client.send(nextRequest, HttpResponse.BodyHandlers.ofInputStream())
                statusCode = currentResponse.statusCode()
                redirectCount++
            }

            val finalResponse = if (redirectChain.isEmpty()) response else currentResponse
            val finalStatusCode = if (redirectChain.isEmpty()) response.statusCode() else statusCode

            val finalContentType = finalResponse.headers().firstValue("Content-Type").orElse("Unknown")
            val finalServer = finalResponse.headers().firstValue("Server").orElse("Unknown")
            val finalHeaders = finalResponse.headers().map().mapValues { (_, values) -> values.joinToString(", ") }
            val finalBody = readBody(finalResponse)
            val title = if (finalContentType.contains("text/html", ignoreCase = true)) {
                HtmlParser.extractTitle(finalBody.text)
            } else {
                "Not found"
            }

            val statusMessage = getStatusMessage(finalStatusCode)

            UrlMetadata(
                url = finalResponse.request().uri().toString(),
                statusCode = finalStatusCode,
                status = statusMessage,
                responseTimeMs = (System.nanoTime() - startTime) / 1_000_000,
                contentType = finalContentType,
                server = finalServer,
                title = title,
                headers = finalHeaders,
                redirectChain = redirectChain,
                bodySizeBytes = finalBody.sizeBytes,
                dnsLookupTimeMs = dnsTime,
                tlsVersion = sslInfo?.tlsVersion,
                certSubject = sslInfo?.certSubject,
                certIssuer = sslInfo?.certIssuer,
                certValidFrom = sslInfo?.certValidFrom,
                certValidTo = sslInfo?.certValidTo,
                method = normalizedMethod
            )
        }.recoverCatching { e ->
            throw when (e) {
                is UnknownHostException -> Exception("Unknown host")
                is SocketTimeoutException -> Exception("Connection timed out")
                is ConnectException -> Exception("Connection refused")
                is javax.net.ssl.SSLException -> Exception("SSL error")
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    Exception("Request interrupted")
                }
                is ResponseTooLargeException -> Exception(e.message)
                is IllegalArgumentException -> Exception("Invalid URL.")
                else -> Exception("Failed: ${e.message}")
            }
        }
    }

    private fun readBody(response: HttpResponse<InputStream>): BodyRead {
        response.body().use { input ->
            val bytes = input.readNBytes(maxResponseBytes + 1)
            if (bytes.size > maxResponseBytes) {
                throw ResponseTooLargeException("Response body exceeds ${maxResponseBytes} bytes")
            }
            return BodyRead(bytes.toString(Charsets.UTF_8), bytes.size.toLong())
        }
    }

    private fun fetchSslInfo(host: String, port: Int): SslInfo? {
        return try {
            val factory = SSLSocketFactory.getDefault()
            (factory.createSocket() as javax.net.ssl.SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, if (port > 0) port else 443), (timeoutSeconds * 1_000).toInt())
                socket.soTimeout = (timeoutSeconds * 1_000).toInt()
                socket.startHandshake()
                val session: SSLSession = socket.session
                val certs = session.peerCertificates
                val cert = certs.firstOrNull() as? X509Certificate
                SslInfo(
                    tlsVersion = session.protocol,
                    certSubject = cert?.subjectX500Principal?.getName(),
                    certIssuer = cert?.issuerX500Principal?.getName(),
                    certValidFrom = cert?.notBefore?.toString(),
                    certValidTo = cert?.notAfter?.toString()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getStatusMessage(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Unknown"
    }

    private data class SslInfo(
        val tlsVersion: String?,
        val certSubject: String?,
        val certIssuer: String?,
        val certValidFrom: String?,
        val certValidTo: String?
    )

    private class ResponseTooLargeException(message: String) : Exception(message)

    private data class BodyRead(val text: String, val sizeBytes: Long)

    private companion object {
        const val MAX_REDIRECTS = 10
        const val MAX_TIMEOUT_SECONDS = 3600L
        const val DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        val SUPPORTED_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")
    }
}

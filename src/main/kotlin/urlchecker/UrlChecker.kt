package urlchecker

import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

class UrlChecker(private val timeoutSeconds: Long = 30) {

    fun check(url: String, method: String = "GET", requestBody: String? = null): Result<UrlMetadata> {
        return runCatching {
            val startDns = System.nanoTime()
            val host = URI.create(url).host
            InetAddress.getByName(host)
            val dnsTime = (System.nanoTime() - startDns) / 1_000_000

            val sslInfo = if (url.startsWith("https")) fetchSslInfo(host) else null

            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build()

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "UrlChecker/1.0")

            val request = when (method.uppercase()) {
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
                else -> builder.GET()
            }.build()

            val startTime = System.currentTimeMillis()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val elapsed = System.currentTimeMillis() - startTime

        val contentType = response.headers().firstValue("Content-Type").orElse("Unknown")
        val server = response.headers().firstValue("Server").orElse("Unknown")

        val headers = mutableMapOf<String, String>()
            response.headers().map().forEach { (name, values) ->
                headers[name] = values.joinToString(", ")
            }

            val redirectChain = mutableListOf<String>()
            var currentResponse = response
            var currentUrl = url
            var statusCode = response.statusCode()

            while (statusCode in 301..308 || statusCode == 307 || statusCode == 308) {
                val location = currentResponse.headers().firstValue("Location").orElse(null)
                if (location == null) break
                redirectChain.add(currentResponse.request().uri().toString())
                currentUrl = if (location.startsWith("http")) location else URI.create(currentUrl).resolve(location).toString()
                val nextRequest = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "UrlChecker/1.0")
                    .GET()
                    .build()
                currentResponse = client.send(nextRequest, HttpResponse.BodyHandlers.ofString())
                statusCode = currentResponse.statusCode()
            }

            val finalResponse = if (redirectChain.isEmpty()) response else currentResponse
            val finalStatusCode = if (redirectChain.isEmpty()) response.statusCode() else statusCode

            val title = if (contentType.contains("text/html")) {
                HtmlParser.extractTitle(finalResponse.body())
            } else {
                "Not found"
            }

            val statusMessage = getStatusMessage(finalStatusCode)

            UrlMetadata(
                url = finalResponse.request().uri().toString(),
                statusCode = finalStatusCode,
                status = statusMessage,
                responseTimeMs = elapsed,
                contentType = contentType,
                server = server,
                title = title,
                headers = headers,
                redirectChain = redirectChain,
                bodySizeBytes = finalResponse.body().toByteArray().size.toLong(),
                dnsLookupTimeMs = dnsTime,
                tlsVersion = sslInfo?.tlsVersion,
                certSubject = sslInfo?.certSubject,
                certIssuer = sslInfo?.certIssuer,
                certValidFrom = sslInfo?.certValidFrom,
                certValidTo = sslInfo?.certValidTo,
                method = method.uppercase()
            )
        }.recoverCatching { e ->
            throw when (e) {
                is UnknownHostException -> Exception("Unknown host")
                is SocketTimeoutException -> Exception("Connection timed out")
                is ConnectException -> Exception("Connection refused")
                is javax.net.ssl.SSLException -> Exception("SSL error")
                is IllegalArgumentException -> Exception("Invalid URL.")
                else -> Exception("Failed: ${e.message}")
            }
        }
    }

    private fun fetchSslInfo(host: String): SslInfo? {
        return try {
            val factory = SSLSocketFactory.getDefault()
            val socket = factory.createSocket(host, 443) as javax.net.ssl.SSLSocket
            socket.startHandshake()
            val session: SSLSession = socket.session
            val certs = session.peerCertificates
            val cert = certs.firstOrNull() as? X509Certificate
            socket.close()
            SslInfo(
                tlsVersion = session.protocol,
                certSubject = cert?.subjectX500Principal?.getName(),
                certIssuer = cert?.issuerX500Principal?.getName(),
                certValidFrom = cert?.notBefore?.toString(),
                certValidTo = cert?.notAfter?.toString()
            )
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
}

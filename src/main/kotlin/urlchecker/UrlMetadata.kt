package urlchecker

data class UrlMetadata(
    val url: String,
    val statusCode: Int,
    val status: String,
    val responseTimeMs: Long,
    val contentType: String,
    val server: String,
    val title: String,
    val headers: Map<String, String>,
    val redirectChain: List<String> = emptyList(),
    val bodySizeBytes: Long = 0,
    val dnsLookupTimeMs: Long = 0,
    val tlsVersion: String? = null,
    val certSubject: String? = null,
    val certIssuer: String? = null,
    val certValidFrom: String? = null,
    val certValidTo: String? = null,
    val method: String = "GET"
)

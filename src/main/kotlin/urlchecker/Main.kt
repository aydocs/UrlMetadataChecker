package urlchecker

import java.io.File
import java.net.URI

const val VERSION = "1.1.0"

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    if (args.contains("--version")) {
        println("UrlChecker $VERSION")
        return
    }

    val urls = mutableListOf<String>()
    var showHeaders = false
    var showJson = false
    var verbose = false
    var method = "GET"
    var requestBody: String? = null
    var outputFile: String? = null
    var batchFile: String? = null
    var timeout = 30L

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--headers" -> showHeaders = true
            "--json" -> showJson = true
            "--verbose", "-v" -> verbose = true
            "--method", "-m" -> {
                if (i + 1 < args.size) {
                    method = args[i + 1].uppercase()
                    i++
                }
            }
            "--data", "-d" -> {
                if (i + 1 < args.size) {
                    requestBody = args[i + 1]
                    i++
                }
            }
            "--output", "-o" -> {
                if (i + 1 < args.size) {
                    outputFile = args[i + 1]
                    i++
                }
            }
            "--batch", "-b" -> {
                if (i + 1 < args.size) {
                    batchFile = args[i + 1]
                    i++
                }
            }
            "--timeout" -> {
                if (i + 1 < args.size) {
                    timeout = args[i + 1].toLongOrNull() ?: 30L
                    i++
                }
            }
            else -> {
                if (!args[i].startsWith("-")) {
                    urls.add(args[i])
                }
            }
        }
        i++
    }

    if (batchFile != null) {
        val file = File(batchFile)
        if (!file.exists()) {
            println("Error: Batch file not found: $batchFile")
            return
        }
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                urls.add(trimmed)
            }
        }
    }

    if (urls.isEmpty()) {
        println("Error: No URLs provided.")
        printUsage()
        return
    }

    val checker = UrlChecker(timeout)
    val results = mutableListOf<String>()
    val validMethods = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")

    if (!validMethods.contains(method)) {
        println("Error: Unsupported method '$method'. Supported: ${validMethods.joinToString(", ")}")
        return
    }

    urls.forEach { url ->
        if (!isValidUrl(url)) {
            val error = "Error: Invalid URL: $url"
            println(error)
            results.add(error)
            return@forEach
        }

        if (verbose) {
            println("${BLUE}Checking: $url${RESET}")
        }

        val result = checker.check(url, method, requestBody)

        result.onSuccess { metadata ->
            val output = if (showJson) {
                printJson(metadata)
            } else {
                printDefault(metadata, verbose) + if (showHeaders) printHeaders(metadata) else ""
            }
            println(output)
            results.add(output)
        }.onFailure { error ->
            val errorMsg = "Error: ${error.message}"
            println(errorMsg)
            results.add(errorMsg)
        }
    }

    outputFile?.let { path ->
        File(path).writeText(results.joinToString("\n"))
        println("\n${GREEN}Results saved to: $path${RESET}")
    }
}

internal fun isValidUrl(url: String): Boolean {
    return try {
        val uri = URI.create(url)
        uri.scheme != null && uri.host != null
    } catch (_: Exception) {
        false
    }
}

private fun printDefault(metadata: UrlMetadata, verbose: Boolean): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("${CYAN}URL Metadata Checker${RESET}")
    sb.appendLine("─".repeat(30))
    sb.appendLine()
    sb.appendLine("URL           : ${metadata.url}")
    sb.appendLine("Method        : ${metadata.method}")
    sb.appendLine("Status Code   : ${metadata.statusCode}")
    sb.appendLine("Status        : ${metadata.status}")
    sb.appendLine("Response Time : ${metadata.responseTimeMs} ms")
    sb.appendLine("DNS Lookup    : ${metadata.dnsLookupTimeMs} ms")
    sb.appendLine("Content Type  : ${metadata.contentType}")
    sb.appendLine("Server        : ${metadata.server}")
    sb.appendLine("Body Size     : ${formatBytes(metadata.bodySizeBytes)}")
    sb.appendLine("Title         : ${metadata.title}")

    metadata.tlsVersion?.let { tls ->
        sb.appendLine("TLS Version   : $tls")
    }

    metadata.certSubject?.let { cert ->
        sb.appendLine("Certificate   : $cert")
    }

    metadata.certValidFrom?.let { from ->
        sb.appendLine("Valid From    : $from")
    }

    metadata.certValidTo?.let { to ->
        sb.appendLine("Valid To      : $to")
    }

    if (metadata.redirectChain.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("${YELLOW}Redirects:${RESET}")
        metadata.redirectChain.forEachIndexed { index, redirect ->
            sb.appendLine("  ${index + 1}. $redirect")
        }
        sb.appendLine("  ${metadata.redirectChain.size + 1}. ${metadata.url}")
    }

    if (verbose) {
        sb.appendLine()
        sb.appendLine("${YELLOW}Headers:${RESET}")
        metadata.headers.toSortedMap().forEach { (name, value) ->
            sb.appendLine("  $name: $value")
        }
    }

    sb.appendLine()
    sb.appendLine("─".repeat(30))
    sb.appendLine()
    return sb.toString()
}

private fun printHeaders(metadata: UrlMetadata): String {
    val sb = StringBuilder()
    sb.appendLine()
    sb.appendLine("${CYAN}HTTP Headers${RESET}")
    sb.appendLine("─".repeat(30))
    sb.appendLine()
    metadata.headers.toSortedMap().forEach { (name, value) ->
        sb.appendLine("$name: $value")
    }
    sb.appendLine()
    return sb.toString()
}

private fun String.toJsonString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun printJson(metadata: UrlMetadata): String {
    val json = buildString {
        appendLine("{")
        appendLine("  \"url\": \"${metadata.url.toJsonString()}\",")
        appendLine("  \"method\": \"${metadata.method}\",")
        appendLine("  \"statusCode\": ${metadata.statusCode},")
        appendLine("  \"status\": \"${metadata.status.toJsonString()}\",")
        appendLine("  \"responseTimeMs\": ${metadata.responseTimeMs},")
        appendLine("  \"dnsLookupTimeMs\": ${metadata.dnsLookupTimeMs},")
        appendLine("  \"contentType\": \"${metadata.contentType.toJsonString()}\",")
        appendLine("  \"server\": \"${metadata.server.toJsonString()}\",")
        appendLine("  \"bodySizeBytes\": ${metadata.bodySizeBytes},")
        appendLine("  \"title\": \"${metadata.title.toJsonString()}\",")
        appendLine("  \"tlsVersion\": \"${metadata.tlsVersion?.toJsonString() ?: ""}\",")
        appendLine("  \"certSubject\": \"${metadata.certSubject?.toJsonString() ?: ""}\",")
        appendLine("  \"certIssuer\": \"${metadata.certIssuer?.toJsonString() ?: ""}\",")
        appendLine("  \"certValidFrom\": \"${metadata.certValidFrom?.toJsonString() ?: ""}\",")
        appendLine("  \"certValidTo\": \"${metadata.certValidTo?.toJsonString() ?: ""}\",")
        appendLine("  \"redirectChain\": [${metadata.redirectChain.joinToString(", ") { "\"${it.toJsonString()}\"" }}],")
        appendLine("  \"headers\": {${metadata.headers.entries.sortedBy { it.key }.joinToString(", ") { "\"${it.key.toJsonString()}\": \"${it.value.toJsonString()}\"" }}}")
        append("}")
    }
    return json
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}

private fun printUsage() {
    println("""
        UrlChecker $VERSION - URL Metadata Checker
        
        Usage: urlchecker <URL> [options]
        
        Options:
          --headers          Show HTTP response headers
          --json             Output as JSON
          --timeout <secs>   Set connection timeout in seconds (default: 30)
          --method, -m       HTTP method: GET, POST, PUT, DELETE, HEAD (default: GET)
          --data, -d         Request body for POST/PUT
          --output, -o       Save results to file
          --batch, -b        Check URLs from file (one per line)
          --verbose, -v      Show detailed output
          --version          Show version
        
        Examples:
          urlchecker https://example.com
          urlchecker https://github.com --headers
          urlchecker https://example.com --json
          urlchecker https://example.com --timeout 10
          urlchecker https://api.example.com --method POST --data '{"key":"value"}'
          urlchecker https://example.com --output results.txt
          urlchecker --batch urls.txt
    """.trimIndent())
}

private const val RESET = "\u001B[0m"
private const val RED = "\u001B[31m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val BLUE = "\u001B[34m"
private const val CYAN = "\u001B[36m"

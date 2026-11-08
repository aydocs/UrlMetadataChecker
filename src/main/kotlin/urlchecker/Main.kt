package urlchecker

import java.io.File
import java.net.URI
import java.util.Locale
import kotlin.system.exitProcess

const val VERSION = "1.1.0"

fun main(args: Array<String>) {
    exitProcess(run(args))
}

internal fun run(args: Array<String>): Int {
    if (args.isEmpty()) {
        printUsage()
        return 0
    }

    if (args.contains("--version")) {
        println("UrlChecker $VERSION")
        return 0
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
                    method = args[i + 1].uppercase(Locale.ROOT)
                    i++
                } else {
                    println("Error: --method requires a value")
                    return 2
                }
            }
            "--data", "-d" -> {
                if (i + 1 < args.size) {
                    requestBody = args[i + 1]
                    i++
                } else {
                    println("Error: --data requires a value")
                    return 2
                }
            }
            "--output", "-o" -> {
                if (i + 1 < args.size) {
                    outputFile = args[i + 1]
                    i++
                } else {
                    println("Error: --output requires a file path")
                    return 2
                }
            }
            "--batch", "-b" -> {
                if (i + 1 < args.size) {
                    batchFile = args[i + 1]
                    i++
                } else {
                    println("Error: --batch requires a file path")
                    return 2
                }
            }
            "--timeout" -> {
                if (i + 1 < args.size) {
                    timeout = args[i + 1].toLongOrNull() ?: run {
                        System.err.println("Error: Timeout must be an integer between 1 and 3600")
                        return 2
                    }
                    i++
                    if (timeout !in 1..3600) {
                        System.err.println("Error: Timeout must be between 1 and 3600 seconds")
                        return 2
                    }
                } else {
                    println("Error: --timeout requires a value")
                    return 2
                }
            }
            else -> {
                if (!args[i].startsWith("-")) {
                    urls.add(args[i])
                } else {
                    println("Error: Unknown option '${args[i]}'")
                    return 2
                }
            }
        }
        i++
    }

    if (batchFile != null) {
        val file = File(batchFile)
        if (!file.exists()) {
            println("Error: Batch file not found: $batchFile")
            return 2
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
        return 2
    }

    val checker = UrlChecker(timeout)
    val renderedResults = mutableListOf<String>()
    val jsonResults = mutableListOf<String>()
    var hadErrors = false
    val validMethods = listOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")

    if (!validMethods.contains(method)) {
        System.err.println("Error: Unsupported method '$method'. Supported: ${validMethods.joinToString(", ")}")
        return 2
    }

    urls.forEach { url ->
        if (!isValidUrl(url)) {
            val error = "Error: Invalid URL: $url"
            hadErrors = true
            System.err.println(error)
            if (showJson) jsonResults.add(printJsonError(url, "Invalid URL"))
            else renderedResults.add(error)
            return@forEach
        }

        if (verbose) {
            System.err.println("${BLUE}Checking: $url${RESET}")
        }

        val result = checker.check(url, method, requestBody)

        result.onSuccess { metadata ->
            if (showJson) {
                jsonResults.add(printJson(metadata))
            } else {
                val output = printDefault(metadata, verbose) + if (showHeaders) printHeaders(metadata) else ""
                renderedResults.add(output)
            }
        }.onFailure { error ->
            hadErrors = true
            val errorMsg = "Error: ${error.message}"
            System.err.println(errorMsg)
            if (showJson) jsonResults.add(printJsonError(url, error.message ?: "Unknown error"))
            else renderedResults.add(errorMsg)
        }
    }

    val output = if (showJson) {
        if (jsonResults.size == 1) jsonResults.single() else "[${jsonResults.joinToString(",")}]"
    } else {
        renderedResults.joinToString("\n")
    }

    outputFile?.let { path ->
        runCatching { File(path).writeText(output) }
            .onFailure {
                System.err.println("Error: Could not write output file '$path': ${it.message}")
                return 2
            }
        System.err.println("${GREEN}Results saved to: $path${RESET}")
    }
    if (outputFile == null && output.isNotEmpty()) {
        println(output)
    }
    return if (hadErrors) 1 else 0
}

internal fun isValidUrl(url: String): Boolean {
    return try {
        val uri = URI.create(url)
        uri.scheme != null && uri.scheme.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
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

private fun String.toJsonString(): String = buildString {
    for (character in this@toJsonString) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

internal fun printJsonForTest(metadata: UrlMetadata): String = printJson(metadata)

private fun printJsonError(url: String, message: String): String =
    "{\"url\":\"${url.toJsonString()}\",\"error\":\"${message.toJsonString()}\"}"

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
          --method, -m       HTTP method: GET, POST, PUT, DELETE, HEAD, PATCH (default: GET)
          --data, -d         Request body for POST/PUT/PATCH
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

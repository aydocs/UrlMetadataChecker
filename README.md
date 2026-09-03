<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/JDK-17+-ED8B00?logo=openjdk&logoColor=white" alt="JDK 17">
  <img src="https://img.shields.io/badge/License-MIT-green" alt="License">
</p>

<h1 align="center">UrlChecker</h1>

<p align="center">
  A command-line tool built with Kotlin for checking URL metadata.
  Get status, headers, TLS info, redirects, and more.
</p>

---

## Preview

```
$ urlchecker https://example.com

URL Metadata Checker
──────────────────────────────

URL           : https://example.com
Method        : GET
Status Code   : 200
Status        : OK
Response Time : 243 ms
DNS Lookup    : 6 ms
Content Type  : text/html
Server        : cloudflare
Body Size     : 559 B
Title         : Example Domain
TLS Version   : TLSv1.3
Certificate   : CN=example.com
Valid From    : Thu Jul 30 01:10:08 TRT 2026
Valid To      : Wed Oct 28 01:17:21 TRT 2026

──────────────────────────────
```

## Features

- HTTP/HTTPS metadata check (GET, POST, PUT, DELETE, HEAD)
- Status code, response time, content type, server, title
- DNS lookup time
- Response body size
- Redirect chain tracking
- HTTPS certificate info (subject, issuer, validity dates)
- TLS version detection
- `--headers` — show all response headers
- `--json` — output as clean JSON
- `--verbose` — detailed output with headers
- `--method` — choose HTTP method
- `--data` — request body for POST/PUT
- `--output` — save results to file
- `--batch` — check multiple URLs from file
- `--timeout` — custom connection timeout
- Graceful error handling

## Requirements

- **JDK 17** or newer
- **Kotlin 1.9+**
- **Gradle** (wrapper included)

## Quick Start

```bash
git clone https://github.com/aydocs/UrlMetadataChecker.git
cd UrlMetadataChecker
./gradlew build
```

## Usage

```bash
# Basic check
./gradlew run --args="https://example.com"

# Show response headers
./gradlew run --args="https://github.com --headers"

# JSON output
./gradlew run --args="https://example.com --json"

# Verbose mode
./gradlew run --args="https://example.com --verbose"

# POST request with body
./gradlew run --args="https://api.example.com --method POST --data '{\"key\":\"value\"}'"

# Save results to file
./gradlew run --args="https://example.com --output results.txt"

# Batch check from file
./gradlew run --args="--batch urls.txt"
```

## Options

| Option | Description |
|--------|-------------|
| `<URL>` | Target URL to check |
| `--headers` | Display all HTTP response headers |
| `--json` | Output result as JSON |
| `--verbose, -v` | Detailed output |
| `--method, -m` | HTTP method: GET, POST, PUT, DELETE, HEAD |
| `--data, -d` | Request body for POST/PUT |
| `--output, -o` | Save results to file |
| `--batch, -b` | Check URLs from file (one per line) |
| `--timeout <s>` | Connection timeout in seconds (default: 30) |
| `--version` | Show version |

## Batch File Format

```
# urls.txt
https://example.com
https://github.com
https://kotlinlang.org
```

## Project Structure

```
src/
├── main/kotlin/urlchecker/
│   ├── Main.kt          CLI entry point, argument parsing, output formatting
│   ├── UrlChecker.kt    HTTP client, response processing, error handling
│   ├── UrlMetadata.kt   Data class for metadata storage
│   └── HtmlParser.kt    HTML title extraction
└── test/kotlin/urlchecker/
    └── UrlCheckerTest.kt   Unit tests
```

## Testing

```bash
./gradlew test
```

## License

[MIT](LICENSE)

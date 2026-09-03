package urlchecker

object HtmlParser {

    fun extractTitle(html: String): String {
        val titlePattern = Regex(
            """<title\s*[^>]*>(.*?)</title>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val match = titlePattern.find(html)
            ?: return "Not found"
        return match.groupValues[1].trim().ifEmpty { "Not found" }
    }
}

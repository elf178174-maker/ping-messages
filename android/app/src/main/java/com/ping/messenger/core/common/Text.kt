package com.ping.messenger.core.common

/** Helpers for the small amount of text parsing the app does locally. */

private val EMOJI_ONLY = Regex(
    "^[\\p{So}\\p{Cn}\\uFE0F\\u200D\\u20E3\\p{IsEmoji_Presentation}\\s]+$",
    RegexOption.IGNORE_CASE,
)

private val URL_REGEX = Regex(
    """(?i)\bhttps?://[-\w+&@#/%?=~|!:,.;]*[-\w+&@#/%=~|]""",
)

private val MENTION_REGEX = Regex("""@([A-Za-z0-9_]{3,24})""")

object TextUtils {

    /**
     * Number of emoji in a message that is nothing but emoji, or 0 if it contains other text.
     * Drives the large-emoji rendering in [com.ping.messenger.ui.theme.emojiOnlyStyle].
     */
    fun emojiOnlyCount(text: String, limit: Int = 3): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 24) return 0
        if (trimmed.any { it.isLetterOrDigit() }) return 0

        var count = 0
        var index = 0
        while (index < trimmed.length) {
            val codePoint = trimmed.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            if (!Character.isWhitespace(codePoint) && !isJoiner(codePoint)) {
                if (!isEmojiCodePoint(codePoint)) return 0
                count++
                if (count > limit) return 0
            }
            index += charCount
        }
        return count
    }

    private fun isJoiner(codePoint: Int): Boolean =
        codePoint == 0x200D || codePoint == 0xFE0F || codePoint == 0xFE0E ||
            codePoint in 0x1F3FB..0x1F3FF || codePoint == 0x20E3

    private fun isEmojiCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x1F300..0x1FAFF, in 0x2600..0x27BF, in 0x1F000..0x1F2FF,
        in 0x2190..0x21FF, in 0x2B00..0x2BFF, in 0xE000..0xF8FF,
        -> true
        else -> false
    }

    /** All http(s) URLs in [text], in the order they appear. */
    fun extractUrls(text: String): List<String> =
        URL_REGEX.findAll(text).map { it.value }.toList()

    fun firstUrl(text: String): String? = URL_REGEX.find(text)?.value

    /** Usernames mentioned with an @ prefix, without the @. */
    fun extractMentions(text: String): List<String> =
        MENTION_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Ranges of [text] that are @mentions, for span styling. */
    fun mentionRanges(text: String): List<IntRange> =
        MENTION_REGEX.findAll(text).map { it.range }.toList()

    fun urlRanges(text: String): List<IntRange> =
        URL_REGEX.findAll(text).map { it.range }.toList()

    /** Collapses a message to one line for the chat list and notifications. */
    fun singleLine(text: String, max: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    /** "Ada Lovelace" -> "AL"; used by the avatar fallback. */
    fun initials(name: String, fallback: String = "?"): String = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { fallback }
}

/**
 * Sanitises user input before it reaches an SQLite `MATCH` clause.
 *
 * FTS4's query language treats `"`, `*`, `:`, `-`, `^`, `(`, `)`, `AND`, `OR` and `NEAR` as
 * operators, so an unescaped apostrophe or a stray quote in a search box turns into a syntax
 * error rather than zero results. Everything that is not a word character is dropped and each
 * remaining token gets a prefix wildcard, which is the behaviour a search box implies.
 */
object FtsQuery {

    fun sanitise(raw: String): String? {
        val tokens = raw
            .split(Regex("[^\\p{L}\\p{N}_]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"*" }
    }
}

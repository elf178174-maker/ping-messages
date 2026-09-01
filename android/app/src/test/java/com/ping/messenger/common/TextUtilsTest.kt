package com.ping.messenger.common

import com.google.common.truth.Truth.assertThat
import com.ping.messenger.core.common.FtsQuery
import com.ping.messenger.core.common.TextUtils
import com.ping.messenger.core.common.formatBytes
import org.junit.Test

class TextUtilsTest {

    @Test
    fun `detects emoji-only messages and counts them`() {
        assertThat(TextUtils.emojiOnlyCount("👍")).isEqualTo(1)
        assertThat(TextUtils.emojiOnlyCount("😀😀")).isEqualTo(2)
        assertThat(TextUtils.emojiOnlyCount("😀 😀 😀")).isEqualTo(3)
    }

    @Test
    fun `text mixed with emoji is not emoji-only`() {
        assertThat(TextUtils.emojiOnlyCount("nice 👍")).isEqualTo(0)
        assertThat(TextUtils.emojiOnlyCount("hello")).isEqualTo(0)
        assertThat(TextUtils.emojiOnlyCount("")).isEqualTo(0)
    }

    @Test
    fun `more emoji than the limit falls back to normal rendering`() {
        // Beyond three, large rendering would overflow a narrow screen, so the
        // count reports zero and the bubble uses the normal body style.
        assertThat(TextUtils.emojiOnlyCount("😀😀😀😀")).isEqualTo(0)
    }

    @Test
    fun `extracts urls in order`() {
        val text = "see https://example.com and http://other.example/path?q=1 too"
        assertThat(TextUtils.extractUrls(text))
            .containsExactly("https://example.com", "http://other.example/path?q=1")
            .inOrder()
    }

    @Test
    fun `extracts mentions without the at sign and deduplicates`() {
        assertThat(TextUtils.extractMentions("hi @ada and @grace and @ada"))
            .containsExactly("ada", "grace")
    }

    @Test
    fun `mention extraction ignores too-short handles`() {
        assertThat(TextUtils.extractMentions("@ab is too short")).isEmpty()
    }

    @Test
    fun `single line collapses whitespace and truncates`() {
        assertThat(TextUtils.singleLine("a\n\n  b   c")).isEqualTo("a b c")
        val long = "x".repeat(200)
        val result = TextUtils.singleLine(long, max = 20)
        assertThat(result).hasLength(20)
        assertThat(result).endsWith("…")
    }

    @Test
    fun `initials take the first letter of the first two words`() {
        assertThat(TextUtils.initials("Ada Lovelace")).isEqualTo("AL")
        assertThat(TextUtils.initials("Ada")).isEqualTo("A")
        assertThat(TextUtils.initials("  ada  byron  lovelace ")).isEqualTo("AB")
        assertThat(TextUtils.initials("")).isEqualTo("?")
    }

    @Test
    fun `fts sanitiser quotes tokens and adds prefix wildcards`() {
        assertThat(FtsQuery.sanitise("hello world")).isEqualTo("\"hello\"* \"world\"*")
    }

    @Test
    fun `fts sanitiser strips characters that would be operators`() {
        // An apostrophe or a quote reaching MATCH unescaped is a syntax error,
        // not zero results — which is why this strips rather than escapes.
        assertThat(FtsQuery.sanitise("it's \"quoted\" -minus")).isEqualTo("\"it\"* \"s\"* \"quoted\"* \"minus\"*")
    }

    @Test
    fun `fts sanitiser returns null when nothing searchable remains`() {
        assertThat(FtsQuery.sanitise("   ")).isNull()
        assertThat(FtsQuery.sanitise("***")).isNull()
    }

    @Test
    fun `formats byte counts`() {
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(1536)).isEqualTo("1.5 kB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5.0 MB")
        assertThat(formatBytes(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB")
    }
}

package com.tuki.sveta.data.telegram

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.OffsetDateTime

data class ChannelPost(val channel: String, val id: Long, val text: String, val at: Instant)

/** Reads posts out of the public web preview at https://t.me/s/<channel>. */
object TelegramParser {

    fun parse(html: String, channel: String): List<ChannelPost> =
        Jsoup.parse(html).select(".tgme_widget_message[data-post]").mapNotNull { message ->
            val id = message.attr("data-post").substringAfterLast('/').toLongOrNull() ?: return@mapNotNull null
            // Media-only posts have no text block; the text block is nested inside a wrapper of the same class.
            val text = message.selectFirst(".tgme_widget_message_text")?.let(::plainText).orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val at = message.selectFirst(".tgme_widget_message_date time[datetime]")
                ?.attr("datetime")
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
                ?: return@mapNotNull null
            ChannelPost(channel, id, text, at)
        }.sortedBy { it.id }

    /** Whether the page is a channel preview at all (private or missing channels redirect to a stub). */
    fun isChannelPage(html: String): Boolean = html.contains("tgme_channel_info") || html.contains("tgme_widget_message")

    /** Accepts "@name", "t.me/name", "https://t.me/s/name" and returns the bare username. */
    fun normalizeChannel(input: String): String? {
        val name = input.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("t.me/").removePrefix("telegram.me/")
            .removePrefix("s/")
            .removePrefix("@")
            .substringBefore('/').substringBefore('?')
        return name.takeIf { USERNAME.matches(it) }
    }

    private fun plainText(element: Element): String {
        val copy = element.clone()
        copy.select("br").forEach { it.after("\n") }
        return copy.wholeText().lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val USERNAME = Regex("[A-Za-z][A-Za-z0-9_]{3,31}")
}

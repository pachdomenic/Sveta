package com.tuki.sveta.data

import com.tuki.sveta.data.telegram.TelegramParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Runs against a trimmed copy of a real https://t.me/s/dtek_ua page. */
class TelegramParserTest {

    private val html = checkNotNull(javaClass.getResource("/telegram-channel.html")).readText()

    @Test
    fun `posts are parsed oldest first with ids and timestamps`() {
        val posts = TelegramParser.parse(html, "dtek_ua")
        assertEquals(listOf(4801L, 4803L, 4819L, 4820L), posts.map { it.id })
        assertEquals(Instant.parse("2026-11-09T18:06:06Z"), posts.last().at)
        assertTrue(posts.all { it.channel == "dtek_ua" })
    }

    @Test
    fun `text keeps line breaks and drops markup`() {
        val text = TelegramParser.parse(html, "dtek_ua").last().text
        assertTrue(text.contains("Без світла залишилась велика частина Дніпра"))
        assertTrue(text.contains("\n"))
        assertFalse(text.contains("<"))
        // nested wrapper of the same class must not duplicate the text
        assertEquals(1, Regex("Атака спричинила").findAll(text).count())
    }

    @Test
    fun `non channel pages are detected`() {
        assertTrue(TelegramParser.isChannelPage(html))
        assertFalse(TelegramParser.isChannelPage("<html><body><div class=\"tgme_page\">Join</div></body></html>"))
        assertTrue(TelegramParser.parse("<html></html>", "x").isEmpty())
    }

    @Test
    fun `channel names are normalised from links and handles`() {
        assertEquals("dtek_ua", TelegramParser.normalizeChannel("@dtek_ua"))
        assertEquals("dtek_ua", TelegramParser.normalizeChannel("https://t.me/dtek_ua"))
        assertEquals("dtek_ua", TelegramParser.normalizeChannel("t.me/s/dtek_ua?before=100"))
        assertEquals("dtek_ua", TelegramParser.normalizeChannel("  dtek_ua/4820 "))
        assertNull(TelegramParser.normalizeChannel("+AbCdEfInvite"))
        assertNull(TelegramParser.normalizeChannel("ab"))
        assertNull(TelegramParser.normalizeChannel("канал"))
    }
}

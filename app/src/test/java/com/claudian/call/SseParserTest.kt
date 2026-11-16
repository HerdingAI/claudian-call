package com.claudian.call

import okio.Buffer
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SseParserTest {
    private val sessions = mutableListOf<String>()
    private val statuses = mutableListOf<String>()
    private val texts = mutableListOf<String>()
    private val dones = mutableListOf<Pair<String?, String>>()

    private fun parser() = SseParser(
        { sessions.add(it) }, { statuses.add(it) }, { texts.add(it) }, { sid, t -> dones.add(sid to t) }
    )

    private fun run(raw: String) {
        parser().readAll(Buffer().writeUtf8(raw))
    }

    @Test fun normalStream() {
        run(
            "event: session\ndata: {\"sessionId\":\"abc\"}\n\n" +
                "event: status\ndata: {\"status\":\"thinking\"}\n\n" +
                "event: text\ndata: {\"fullText\":\"Hello\"}\n\n" +
                "event: done\ndata: {\"sessionId\":\"abc\",\"text\":\"Hello\"}\n\n"
        )
        assertEquals(listOf("abc"), sessions)
        assertEquals(listOf("thinking"), statuses)
        assertEquals(listOf("Hello"), texts)
        assertEquals(listOf<Pair<String?, String>>("abc" to "Hello"), dones)
    }

    @Test fun doneWithNullStringSessionIdBecomesNull() {
        run("event: done\ndata: {\"sessionId\":\"null\",\"text\":\"x\"}\n\n")
        assertEquals(listOf<Pair<String?, String>>(null to "x"), dones)
    }

    @Test fun emptySessionAndStatusAreNotDispatched() {
        run("event: session\ndata: {\"sessionId\":\"\"}\n\nevent: status\ndata: {}\n\n")
        assertTrue(sessions.isEmpty())
        assertTrue(statuses.isEmpty())
    }

    @Test fun defaultEventNameIsMessageAndIgnored() {
        run("data: {\"fullText\":\"x\"}\n\n")
        assertTrue(texts.isEmpty())
    }

    @Test fun trailingEventWithoutBlankLineIsFlushed() {
        run("event: text\ndata: {\"fullText\":\"tail\"}")
        assertEquals(listOf("tail"), texts)
    }

    @Test fun eventNameResetsBetweenEvents() {
        run("event: text\ndata: {\"fullText\":\"a\"}\n\ndata: {\"fullText\":\"b\"}\n\n")
        assertEquals(listOf("a"), texts) // second event has default name "message"
    }

    @Test fun noSpaceAfterColon() {
        run("event:text\ndata:{\"fullText\":\"a\"}\n\n")
        assertEquals(listOf("a"), texts)
    }

    @Test fun crlfLineEndings() {
        run("event: text\r\ndata: {\"fullText\":\"crlf\"}\r\n\r\nevent: done\r\ndata: {\"text\":\"d\"}\r\n\r\n")
        assertEquals(listOf("crlf"), texts)
        assertEquals(listOf<Pair<String?, String>>(null to "d"), dones)
    }

    @Test fun commentAndKeepaliveLinesAreIgnored() {
        run(": keepalive\nevent: text\n: another comment\ndata: {\"fullText\":\"k\"}\n\n: ping\n\n")
        assertEquals(listOf("k"), texts)
    }

    @Test fun commentOnlyBlockDispatchesNothing() {
        run(": ping\n\n: ping\n\n")
        assertTrue(texts.isEmpty() && sessions.isEmpty() && dones.isEmpty())
    }

    @Test fun unknownEventNameIsIgnored() {
        run("event: tool_use\ndata: {\"x\":1}\n\nevent: text\ndata: {\"fullText\":\"after\"}\n\n")
        assertEquals(listOf("after"), texts)
    }

    @Test fun errorEventThrowsIOException() {
        try {
            run("event: error\ndata: {\"error\":\"quota exhausted\"}\n\n")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("quota exhausted", e.message)
        }
    }

    @Test fun errorEventWithoutMessageUsesDefault() {
        try {
            run("event: error\ndata: {}\n\n")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Chat error", e.message)
        }
    }

    // KNOWN BUG — flips in M4: multi-line data is not concatenated; the LAST data line wins,
    // so a JSON payload split across lines is truncated/garbled instead of joined with "\n".
    @Test fun multiLineDataKeepsOnlyLastLine() {
        run("event: text\ndata: {\"fullText\":\"first\"}\ndata: {\"fullText\":\"second\"}\n\n")
        assertEquals(listOf("second"), texts)
    }

    @Test fun nonJsonDataLineIsIgnoredWithoutKillingStream() {
        run("event: text\ndata: not json\n\nevent: text\ndata: {\"fullText\":\"recovered\"}\n\n")
        assertEquals(listOf("recovered"), texts)
    }

    @Test fun doneSentinelDataIsTolerated() {
        run("data: [DONE]\n\n")
        assertTrue(texts.isEmpty())
    }

    @Test fun dataWithoutEventBlankLineOnlyFlushesWhenDataPresent() {
        run("event: text\n\n")
        assertTrue(texts.isEmpty())
    }

    @Test fun emptyInput() {
        run("")
        assertTrue(texts.isEmpty())
    }
}

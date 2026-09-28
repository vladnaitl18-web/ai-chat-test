package dev.test.aichat

import dev.test.aichat.network.*
import org.junit.Assert.*
import org.junit.Test

class SseParserTest {
    @Test fun commentsCrLfMultilineDataAndEventIdsAreParsed() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(events::add)
        listOf(": heartbeat", "id: 12\r", "event: delta", "data: первая", "data: вторая", "\r", "event: done", "data: {}", "").forEach(parser::line)
        assertEquals(listOf(SseEvent("12", "delta", "первая\nвторая"), SseEvent("12", "done", "{}")), events)
    }
    @Test fun incompleteEventIsNotDispatchedAndEmptyCommentsAreIgnored() {
        val events = mutableListOf<SseEvent>()
        val parser = SseParser(events::add)
        listOf(": comment", "", "data: interrupted").forEach(parser::line)
        assertTrue(events.isEmpty())
    }
}

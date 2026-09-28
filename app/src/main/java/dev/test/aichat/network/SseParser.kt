package dev.test.aichat.network

/** SSE framing only: preserves multiline data, ignores comments, dispatches on a blank line. */
data class SseEvent(val id: String?, val type: String, val data: String)
class SseParser(private val emit: (SseEvent) -> Unit) {
    private var id: String? = null
    private var type = "message"
    private val data = mutableListOf<String>()
    fun line(raw: String) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            if (data.isNotEmpty()) emit(SseEvent(id, type, data.joinToString("\n")))
            type = "message"; data.clear()
            return
        }
        if (line.startsWith(":")) return
        val field = line.substringBefore(':')
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when (field) {
            "data" -> data.add(value)
            "event" -> type = value
            "id" -> if ('\u0000' !in value) id = value
        }
    }
}

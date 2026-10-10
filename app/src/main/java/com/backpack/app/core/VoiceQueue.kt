package com.backpack.app.core

/**
 * 1.2.1: who may speak, and when (pure logic, no Android).
 *
 * - Navigation instructions always go first: they cut whatever is being
 *   said, and nothing waits in front of them.
 * - Info (sight heads-ups, Wikipedia readings) is queued, first come first
 *   served; one never cuts another. A cut item resumes after the
 *   instruction from the start of the interrupted sentence.
 * - A heads-up only starts in a gap between instructions (the last one
 *   [GAP_AFTER_MS] ago, the next maneuver [GAP_BEFORE_S] away); a heads-up
 *   no longer valid (place passed) is dropped silently.
 * - 1.4.1 DANGER (danger zone...): never waits for a gap and goes before
 *   any other info (the one being said resumes after it); it still never
 *   cuts an instruction or a sentence already started.
 */
class VoiceQueue {

    enum class Kind { HEADS_UP, READING, DANGER }

    class Item(
        val kind: Kind,
        /** Worded when it starts (e.g. "in about 2 minutes" still right after waiting). */
        private val text: () -> String,
        /** Still worth saying (e.g. the place is still ahead). */
        val valid: () -> Boolean = { true },
        /** Called once, when it starts to be said (e.g. show its message). */
        val onStart: () -> Unit = {},
        /** 1.3.0: language of the text when not the voice language (e.g. "en"). */
        val lang: String? = null
    ) {
        constructor(kind: Kind, text: String, lang: String? = null) : this(kind, { text }, { true }, {}, lang)
        var sentences: List<String> = emptyList()
            private set
        internal var index = 0
        internal var started = false
        internal fun start() { started = true; sentences = sentences(text()); onStart() }
    }

    /** What the speaker should do now. */
    sealed class Say {
        data class Sentence(val item: Item, val index: Int, val text: String) : Say()
        object Nothing : Say()
    }

    private val queue = ArrayDeque<Item>()
    var current: Item? = null
        private set
    /** An info sentence is being spoken right now. */
    var infoSpeaking = false
        private set
    var navSpeaking = false
        private set
    var lastNavDoneMs = 0L
        private set

    fun add(item: Item) {
        if (item.kind != Kind.DANGER) { queue.addLast(item); return }
        // After the dangers already waiting, before everything else.
        val at = queue.indexOfFirst { it.kind != Kind.DANGER }.let { if (it < 0) queue.size else it }
        queue.add(at, item)
    }

    fun pendingCount(kind: Kind): Int = queue.count { it.kind == kind } + (if (current?.kind == kind) 1 else 0)

    /** An instruction starts: it cuts the info sentence (said again later). */
    fun navStarted() { navSpeaking = true; infoSpeaking = false }

    fun navDone(nowMs: Long) { navSpeaking = false; lastNavDoneMs = nowMs }

    /** The current info sentence was said to the end. */
    fun sentenceDone() {
        val c = current ?: return
        infoSpeaking = false
        c.index++
        if (c.index >= c.sentences.size) current = null
    }

    /** Drops every reading (current and queued); true if one was being said. */
    fun dropReadings(): Boolean {
        queue.removeAll { it.kind == Kind.READING }
        val c = current
        if (c != null && c.kind == Kind.READING) { current = null; val was = infoSpeaking; infoSpeaking = false; return was }
        return false
    }

    fun clear() { queue.clear(); current = null; infoSpeaking = false; navSpeaking = false }

    /**
     * Next thing to say, or Nothing (busy, or waiting for a gap).
     * [secondsToNextManeuver] null = not navigating (no gap rule).
     */
    fun next(nowMs: Long, secondsToNextManeuver: Double?): Say {
        if (navSpeaking || infoSpeaking) return Say.Nothing
        var c = current
        if (c != null && c.kind != Kind.READING && !c.valid()) { current = null; c = null }
        // A danger waiting goes first; the item it interrupts resumes after it.
        if (c != null && c.kind != Kind.DANGER && queue.firstOrNull()?.kind == Kind.DANGER) {
            val d = queue.removeFirst()
            queue.addFirst(c)
            queue.addFirst(d)
            current = null
            c = null
        }
        if (c == null) {
            while (true) {
                val head = queue.firstOrNull() ?: return Say.Nothing
                if (!head.valid()) { queue.removeFirst(); continue }
                if (head.kind == Kind.HEADS_UP && !gapOk(nowMs, secondsToNextManeuver)) return Say.Nothing
                queue.removeFirst()
                c = head
                break
            }
            current = c
        }
        val item = c!!
        if (!item.started) item.start()
        if (item.index >= item.sentences.size) { current = null; return next(nowMs, secondsToNextManeuver) }
        infoSpeaking = true
        return Say.Sentence(item, item.index, item.sentences[item.index])
    }

    private fun gapOk(nowMs: Long, secondsToNext: Double?): Boolean {
        if (secondsToNext == null) return true
        return nowMs - lastNavDoneMs >= GAP_AFTER_MS && secondsToNext >= GAP_BEFORE_S
    }

    companion object {
        const val GAP_AFTER_MS = 8000L
        const val GAP_BEFORE_S = 20.0
        private const val MAX_SENTENCE = 280

        /** Text -> sentences (long ones cut at ";" or "," so a resume repeats little). */
        fun sentences(text: String): List<String> {
            val out = ArrayList<String>()
            for (raw in text.trim().split(Regex("(?<=[.!?])\\s+"))) {
                var s = raw.trim()
                while (s.length > MAX_SENTENCE) {
                    val cut = maxOf(s.lastIndexOf("; ", MAX_SENTENCE), s.lastIndexOf(", ", MAX_SENTENCE))
                    val at = if (cut > 40) cut + 1 else s.lastIndexOf(' ', MAX_SENTENCE).takeIf { it > 40 } ?: MAX_SENTENCE
                    out.add(s.substring(0, at).trim())
                    s = s.substring(at).trim()
                }
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}

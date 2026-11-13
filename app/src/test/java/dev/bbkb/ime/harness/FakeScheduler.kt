package dev.bbkb.ime.harness

/**
 * Virtual-time task queue for deferred work: `cancel(tag)` mirrors `Handler.removeMessages`
 * and [advanceMs] runs due tasks in time order, making race scenarios deterministic.
 */
class FakeScheduler {
    data class Task(val dueAtMs: Long, val seq: Long, val tag: String, val action: () -> Unit)

    private var nowMs = 0L
    private var seq = 0L
    private val tasks = mutableListOf<Task>()

    /** Tags cancelled at least once. */
    val cancelledTags = mutableListOf<String>()

    fun nowMs(): Long = nowMs

    fun schedule(delayMs: Long, tag: String, action: () -> Unit) {
        tasks += Task(nowMs + delayMs, seq++, tag, action)
    }

    /** Remove all pending tasks with this tag (Handler.removeMessages semantics). */
    fun cancel(tag: String) {
        cancelledTags += tag
        tasks.removeAll { it.tag == tag }
    }

    fun pendingCount(tag: String? = null): Int =
        if (tag == null) tasks.size else tasks.count { it.tag == tag }

    /** Advance virtual time, running every task that comes due, in order. */
    fun advanceMs(deltaMs: Long) {
        val target = nowMs + deltaMs
        while (true) {
            val next = tasks.filter { it.dueAtMs <= target }
                .minWithOrNull(compareBy({ it.dueAtMs }, { it.seq })) ?: break
            tasks.remove(next)
            nowMs = next.dueAtMs
            next.action()
        }
        nowMs = target
    }
}

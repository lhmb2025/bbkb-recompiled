package dev.bbkb.ime.harness

/**
 * Virtual-time task queue emulating the pipeline's deferred work
 * (UIUpdateHandler delayed messages, worker-thread replies).
 *
 * The harness schedules tasks with a tag; `cancel(tag)` mirrors
 * `Handler.removeMessages` (e.g. `cancelPendingSuggestionUpdates`), and
 * [advanceMs] delivers everything that comes due, in time order — making the
 * race scenarios (a reply landing after the world changed) deterministic.
 */
class FakeScheduler {
    data class Task(val dueAtMs: Long, val seq: Long, val tag: String, val action: () -> Unit)

    private var nowMs = 0L
    private var seq = 0L
    private val tasks = mutableListOf<Task>()

    /** Tags cancelled at least once — lets scenarios assert a cancellation happened. */
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

package app.cloudipix.gallery.core.sync

import kotlinx.coroutines.CompletableDeferred

/** 대기 중인 독립 루트를 우선하고, 남는 자리는 하위 폴더 비교에 배정한다. */
internal class FolderComparisonQueue {
    companion object { const val CAPACITY = 4 }

    private class Request(val id: String) {
        val turn = CompletableDeferred<Unit>()
        var admitted = false
    }

    private val running = mutableMapOf<String, Int>()
    private val waiting = ArrayDeque<Request>()
    private var occupied = 0

    fun root(id: String): Root = Root(id)

    inner class Root(val id: String) {
        suspend fun <T> compare(block: suspend () -> T): T {
            val request = Request(id)
            try {
                synchronized(this@FolderComparisonQueue) {
                    waiting.addLast(request)
                    dispatch()
                }
                request.turn.await()
                return block()
            } finally {
                synchronized(this@FolderComparisonQueue) {
                    if (request.admitted) {
                        decrement(running, id)
                        occupied--
                    } else {
                        waiting.remove(request)
                    }
                    dispatch()
                }
            }
        }
    }

    private fun dispatch() {
        while (occupied < CAPACITY) {
            val next = waiting.firstOrNull { it.id !in running }
                ?: waiting.firstOrNull()
                ?: return
            waiting.remove(next)
            running[next.id] = (running[next.id] ?: 0) + 1
            occupied++
            next.admitted = true
            next.turn.complete(Unit)
        }
    }

    private fun decrement(counts: MutableMap<String, Int>, id: String) {
        val remaining = counts.getValue(id) - 1
        if (remaining == 0) counts.remove(id) else counts[id] = remaining
    }
}

package com.example.ramcleaner

/** Keeps a rolling window of memory samples per app to compute growth ("trending"). */
class TrendTracker(private val windowMs: Long = 60_000L) {
    private val hist = HashMap<String, ArrayDeque<Pair<Long, Long>>>()

    fun record(eaters: List<Eater>, now: Long = System.currentTimeMillis()) {
        val seen = HashSet<String>()
        for (e in eaters) {
            val kb = e.kb ?: continue
            seen += e.pkg
            val q = hist.getOrPut(e.pkg) { ArrayDeque() }
            q.addLast(now to kb)
            while (q.size > 1 && q.first().first < now - windowMs) q.removeFirst()
        }
        hist.keys.retainAll(seen) // forget apps that died
    }

    /** Memory change in kB over the window, or null if there aren't two samples yet. */
    fun deltaKb(pkg: String): Long? =
        hist[pkg]?.takeIf { it.size > 1 }?.let { it.last().second - it.first().second }
}

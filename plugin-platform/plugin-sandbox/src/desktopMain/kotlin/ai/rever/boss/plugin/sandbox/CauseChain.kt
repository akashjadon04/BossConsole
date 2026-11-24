package ai.rever.boss.plugin.sandbox

/**
 * How far any cause walk goes.
 *
 * One fact, not several. This bound lived in four near-identical loops, three of
 * them carrying a comment claiming they matched the others - and they stopped
 * matching, which is how a cause-chain fix landed on one uncontainable check and
 * left its twin flat, so the same wrapped OutOfMemoryError escalated in one place
 * and was contained in the other.
 *
 * It lives in this module rather than in composeApp because attribution needs it
 * too and composeApp depends on plugin-sandbox, not the reverse.
 */
const val MAX_CAUSE_DEPTH = 12

/**
 * This throwable and its causes, nearest first, bounded and cycle-guarded.
 *
 * Wrapping is routine on these paths - an `InvocationTargetException` from a
 * reflective call, a `CompletionException` from a future, Compose's own wrappers -
 * so a question worth asking about a throwable is nearly always worth asking about
 * its causes too.
 *
 * The cycle guard is identity-based and not optional: `initCause` cannot build a
 * loop but an overridden `getCause` can, and a crash handler that hangs is worse
 * than one that misattributes. Eager rather than a `Sequence` because every caller
 * consumes the whole thing and one of them needs it reversed.
 */
fun Throwable.causeChain(max: Int = MAX_CAUSE_DEPTH): List<Throwable> {
    val chain = ArrayList<Throwable>(max)
    var current: Throwable? = this
    while (current != null && chain.size < max && chain.none { it === current }) {
        chain.add(current)
        current = current.cause
    }
    return chain
}

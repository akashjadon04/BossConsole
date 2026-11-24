package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.causeChain

/**
 * Re-exported so this package keeps reading as it did, with one implementation
 * behind it.
 *
 * The walk lives in `plugin-sandbox` because attribution needs it too and
 * composeApp depends on that module, not the reverse. Keeping a typealias-style
 * shim here means the crash package's four callers did not all have to grow an
 * import from another module's package - and, more to the point, there is now
 * exactly one bound and one cycle guard rather than a comment in each copy
 * claiming they match.
 */
internal fun Throwable.chainOfCauses(): List<Throwable> = causeChain()

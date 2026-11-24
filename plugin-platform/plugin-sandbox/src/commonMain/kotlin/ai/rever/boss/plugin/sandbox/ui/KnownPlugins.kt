package ai.rever.boss.plugin.sandbox.ui

/**
 * Every plugin the host has loaded, whether or not it has UI on screen.
 *
 * Crash attribution used to consider only plugins with a *mounted* error
 * boundary, because the boundary registry was the only list of plugin ids this
 * module had. A plugin with nothing on screen was therefore unattributable - and
 * an unattributable `StackOverflowError` escalates to ending the app. That is how
 * a self-recursive method in `terminal-tab` took BOSS down from a single click,
 * with every one of the ~1024 surviving stack frames naming the plugin.
 *
 * Its own object rather than a pair of functions on `PluginCrashRegistry`: this
 * is a different question from crash *state*, and the registry is already at
 * detekt's function ceiling. Common rather than desktop because
 * `PluginCrashInterceptor`, which needs it, is desktop-only while the host
 * installs it from common code.
 */
object KnownPlugins {
    @Volatile
    private var supplier: (() -> Set<String>)? = null

    /**
     * Install the host's loaded-plugin enumeration. Last install wins.
     *
     * Read lazily on the crash path rather than snapshotted, so plugins loaded
     * after startup are covered without anything having to re-register.
     */
    fun install(supplier: () -> Set<String>) {
        this.supplier = supplier
    }

    /**
     * Loaded plugin ids, or empty when nothing has been installed.
     *
     * Never throws. This runs while a crash is being routed, and a supplier
     * reaching into a plugin manager that is mid-teardown must not be the reason
     * the crash handler fails.
     */
    fun ids(): Set<String> = runCatching { supplier?.invoke() }.getOrNull().orEmpty()

    /** Tests share one process; leaving a supplier installed leaks across them. */
    internal fun resetForTest() {
        supplier = null
    }
}

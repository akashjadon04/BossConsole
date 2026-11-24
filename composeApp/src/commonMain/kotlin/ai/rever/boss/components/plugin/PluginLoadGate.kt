package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginApiLevelException
import ai.rever.boss.plugin.loader.PluginBossVersionException
import ai.rever.boss.plugin.loader.PluginSignatureException
import ai.rever.boss.utils.AppVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// A plugin the host refused to load, and what would actually fix it.
//
// WHY THIS EXISTS. `DynamicPluginLoader` refuses a plugin it cannot safely run - a version floor
// this build does not meet, or a jar whose bytes do not match its recorded signature. Both
// refusals are correct. But they only reached an ERROR line in the log, so from the user's side a
// plugin simply stopped existing. That is bad for any plugin and worse for a `systemPlugin`.
//
// Both have happened for real.
//
// fluck-browser 1.2.22 shipped requiring BOSS 9.4.23 while 9.4.22 was current, and every host that
// took the update lost its browser tab - fluck-browser IS the browser tab, so the symptom was "my
// browser is gone":
//
//     PluginBossVersionException: Plugin requires BOSS version 9.4.23 or later,
//                                 but current version is 9.4.22
//
// And a Toolbox jar replaced by hand, while its `.jar.sig` still signed the previous bytes:
//
//     Plugin signature verification failed - plugin will NOT load and will keep being
//     rejected until it is reinstalled or removed
//
// The second is the worse shape: the Toolbox is how a plugin gets reinstalled, so the way out
// vanished with it. Recovering either meant knowing which jar had been overwritten and putting a
// file back by hand - not reasonable to expect, and all of it knowable from the exception the
// loader already throws.
//
// This models the refusals and the ways out. It is deliberately pure - no store, no updater, no
// files - so "what should we offer the user" is answerable in a test.

/**
 * Why the host refused the plugin.
 *
 * `required` and `current` live on the VERSION variants rather than up here: not every refusal is
 * a version comparison. [SignatureRejected] has no pair of numbers to report, and giving it two
 * would mean inventing values for a dialog to print.
 */
sealed interface PluginLoadGate {
    val pluginId: String
    val displayName: String

    /** A refusal that compares what the plugin asks for against what this build provides. */
    sealed interface VersionFloor : PluginLoadGate {
        /** What the plugin asks for. */
        val required: String

        /** What this build has. */
        val current: String
    }

    /** The plugin needs a newer BOSS. Fixed by updating the app, or by going back a plugin version. */
    data class NeedsNewerHost(
        override val pluginId: String,
        override val displayName: String,
        override val required: String,
        override val current: String,
    ) : VersionFloor

    /**
     * The plugin needs a newer plugin API layer.
     *
     * Distinct from [NeedsNewerHost] because the fix is different and much cheaper: the api layer is
     * itself a plugin, hot-swappable without a restart, so this is resolvable in place. Conflating
     * the two would send a user to download a whole application update for something the store can
     * settle in seconds.
     */
    data class NeedsNewerApi(
        override val pluginId: String,
        override val displayName: String,
        override val required: String,
        override val current: String,
    ) : VersionFloor

    /**
     * The jar's bytes do not match the signature recorded beside it, so the loader refused it.
     *
     * A DIFFERENT kind of refusal from the two above, and the remedy is different too: no version
     * is wrong, so neither updating nor reverting helps. What fixes it is fetching the artifact the
     * store actually signed.
     *
     * The failure is silent in a way the version floors are not. Those at least leave a plugin
     * whose jar is intact, so a downgrade has something to go back to; a signature mismatch means
     * the bytes on disk are not the ones the store vouched for, and the host is right to fail
     * closed. But it fails closed with no UI at all - the plugin simply stops existing - and when
     * the plugin is the Toolbox, the thing that vanishes IS the way to reinstall it. That
     * chicken-and-egg is the whole reason this variant exists.
     *
     * [reason] comes from the verifier, not from us: "No trusted key verified the signature" is
     * what a hand-replaced jar produces, and it is more use to whoever reports this than anything
     * this layer could invent.
     */
    data class SignatureRejected(
        override val pluginId: String,
        override val displayName: String,
        val reason: String,
    ) : PluginLoadGate
}

/** Something the user can do about a [PluginLoadGate], with enough detail to label a button. */
sealed interface PluginLoadRemedy {
    /**
     * Update the application. Only offered when an update is actually available AND high enough -
     * "Update BOSS" that lands on a version still below the floor is worse than no button, because
     * the user pays for a restart and the plugin is still missing afterwards.
     */
    data class UpdateHost(
        val availableVersion: String,
    ) : PluginLoadRemedy

    /** Install a newer api plugin from the store. */
    data class UpdateApi(
        val availableVersion: String,
    ) : PluginLoadRemedy

    /**
     * Go back to the plugin version that was working.
     *
     * The remedy that always applies, because it does not depend on anything being published: the
     * jar this one replaced is kept aside precisely so there is a way back. Named with the version
     * so the button can say what it will do rather than "Downgrade".
     */
    data class RevertPlugin(
        val toVersion: String,
    ) : PluginLoadRemedy

    /**
     * Fetch the artifact the store actually signed, replacing the bytes on disk.
     *
     * The only remedy for a signature mismatch. Reverting does not help - the kept-aside jar is a
     * different version, not a correctly-signed copy of this one - and neither does updating the
     * host, since no version is wrong. Named with the version so the button says what it installs.
     *
     * Deliberately available even for a `systemPlugin` the user cannot otherwise reinstall: that is
     * the case it exists for.
     */
    data class ReinstallFromStore(
        val version: String,
    ) : PluginLoadRemedy

    /**
     * Nothing can be offered, and the reason is worth showing.
     *
     * Reached when no update is published yet and no previous jar was kept - which is exactly the
     * position a user is in when a plugin's first release already overshoots their host. Saying so
     * beats an empty dialog or, worse, silence.
     */
    data class NothingAvailable(
        val reason: String,
    ) : PluginLoadRemedy
}

/**
 * Everything the host managed to find that might help, gathered before the decision.
 *
 * A parameter object rather than four more arguments on [remediesFor]. They are all "some version
 * that might fix this" and all optional, so at a call site four bare nullable strings are
 * indistinguishable from one another - and adding the fourth pushed the function past what detekt
 * will accept, which was a fair signal rather than something to baseline away.
 *
 * Every field defaults to null, i.e. "could not be found or was not asked for". A caller only fills
 * in what applies to the gate it has.
 */
data class RemedyOptions(
    /** The app version an update would install, or null when none is available. */
    val hostUpdate: String? = null,
    /** The api-plugin version the store publishes, or null when it cannot be asked. */
    val apiUpdate: String? = null,
    /** The plugin version kept aside from the last replacement, or null when none was. */
    val revertTo: String? = null,
    /** The version the store serves for this plugin, or null when the store cannot be reached. */
    val storeVersion: String? = null,
)

/**
 * What the host can offer for [gate], most useful first.
 *
 * Split by the kind of refusal, because the two share nothing. A version floor is a comparison and
 * has a ladder of fixes; a signature mismatch is not a comparison at all and has exactly one.
 *
 * @param satisfies whether a candidate version meets a required floor. Injected rather than
 *   reimplemented here: the loader already owns that comparison, and a second copy of version
 *   arithmetic is how the two drift into disagreeing about the same pair of numbers.
 */
fun remediesFor(
    gate: PluginLoadGate,
    options: RemedyOptions,
    satisfies: (required: String, candidate: String) -> Boolean,
): List<PluginLoadRemedy> =
    when (gate) {
        is PluginLoadGate.SignatureRejected -> signatureRemedies(options.storeVersion)
        is PluginLoadGate.VersionFloor -> versionFloorRemedies(gate, options, satisfies)
    }

/**
 * The single fix for a signature mismatch: fetch what the store signed.
 *
 * Neither of the version-floor remedies applies. Updating the host cannot help because no version
 * is wrong, and reverting would swap one unverifiable artifact for another - the kept-aside jar is
 * a different version, not a correctly-signed copy of this one.
 *
 * No `satisfies` check either: there is no floor to clear. Any version the store serves is signed,
 * which is the only property that matters - including the version already on disk, since the whole
 * point is replacing bytes that do not match with the ones the store vouched for.
 */
private fun signatureRemedies(storeVersion: String?): List<PluginLoadRemedy> =
    storeVersion?.let { listOf(PluginLoadRemedy.ReinstallFromStore(it)) }
        ?: listOf(
            PluginLoadRemedy.NothingAvailable(
                "The installed file does not match the signature the store recorded for it, and " +
                    "the store cannot be reached to fetch a clean copy. Reinstalling this tool " +
                    "will fix it once you are back online.",
            ),
        )

/** The ladder of fixes for a version floor: forward if it clears the floor, then back. */
private fun versionFloorRemedies(
    gate: PluginLoadGate.VersionFloor,
    options: RemedyOptions,
    satisfies: (required: String, candidate: String) -> Boolean,
): List<PluginLoadRemedy> {
    val remedies = mutableListOf<PluginLoadRemedy>()
    when (gate) {
        is PluginLoadGate.NeedsNewerHost -> {
            // Only when it would actually clear the floor. An update to 9.4.22 does not help a
            // plugin asking for 9.4.23, and offering it wastes a restart to end up here again.
            options.hostUpdate?.takeIf { satisfies(gate.required, it) }?.let {
                remedies += PluginLoadRemedy.UpdateHost(it)
            }
        }

        is PluginLoadGate.NeedsNewerApi -> {
            options.apiUpdate?.takeIf { satisfies(gate.required, it) }?.let {
                remedies += PluginLoadRemedy.UpdateApi(it)
            }
        }
    }
    // Always last, and always offered when it exists: it is the remedy that needs nothing published
    // and no restart, so it is the one that works when everything else is unavailable. Last rather
    // than first because going forward is better than going back when both are possible.
    options.revertTo?.let { remedies += PluginLoadRemedy.RevertPlugin(it) }

    return remedies.ifEmpty {
        listOf(
            PluginLoadRemedy.NothingAvailable(
                when (gate) {
                    is PluginLoadGate.NeedsNewerHost -> {
                        "This needs BOSS ${gate.required}. You have ${gate.current}, " +
                            "no update is available yet, and no earlier version of the plugin was kept."
                    }

                    is PluginLoadGate.NeedsNewerApi -> {
                        "This needs plugin API ${gate.required}. You have ${gate.current}, " +
                            "the store has nothing newer, and no earlier version of the plugin was kept."
                    }
                },
            ),
        )
    }
}

/**
 * The load refusals this session has seen, so something can offer a way out.
 *
 * A process-wide object rather than state on `DynamicPluginManager`, for the same reason
 * `PluginCrashRegistry` is one: the refusal happens during startup plugin loading, long before any
 * window exists to hold it, and the dialog that acts on it is mounted by whichever window opens
 * first. Passing it down would mean threading it through every construction path that can load a
 * plugin.
 *
 * Keyed by plugin id, so a plugin refused on every restart accumulates one entry rather than one
 * per attempt.
 */
object PluginLoadGateRegistry {
    private val _gates = MutableStateFlow<Map<String, PluginLoadGate>>(emptyMap())

    /** Refusals not yet dismissed or resolved, keyed by plugin id. */
    val gates: StateFlow<Map<String, PluginLoadGate>> = _gates.asStateFlow()

    fun record(gate: PluginLoadGate) {
        _gates.value = _gates.value + (gate.pluginId to gate)
    }

    /**
     * Forget the refusal for [pluginId].
     *
     * Called when a remedy has been applied AND when the user dismisses. Both, because a dialog
     * that reappears on every recomposition for a problem the user has decided to live with is
     * worse than the silence this replaced.
     */
    fun clear(pluginId: String) {
        if (_gates.value.containsKey(pluginId)) {
            _gates.value = _gates.value - pluginId
        }
    }

    /** For tests. Nothing in the app should need to drop every refusal at once. */
    internal fun reset() {
        _gates.value = emptyMap()
    }
}

/**
 * Translate a load failure into a [PluginLoadGate], or null when nothing can be offered for it.
 *
 * Null for everything without a fix, on purpose. A corrupt jar, a missing main class or a binary
 * incompatibility have no button that helps, and offering "Update BOSS" for them would send a user
 * through a download and a restart to arrive at the same failure.
 *
 * The display name falls back to the plugin id: the manifest is unavailable here, because the
 * refusal happens before the loader returns anything but the exception, and an id is a worse label
 * than a name but a far better one than nothing.
 */
internal fun loadGateFor(error: Throwable?): PluginLoadGate? =
    when (error) {
        is PluginSignatureException -> {
            val id = error.pluginId
            // Only the id is required: unlike a version floor there is no second value to
            // reason about, so a refusal we can name is a refusal we can offer a fix for.
            if (id.isNullOrBlank()) {
                null
            } else {
                PluginLoadGate.SignatureRejected(
                    pluginId = id,
                    displayName = id,
                    reason =
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "The file does not match its recorded signature.",
                )
            }
        }

        is PluginBossVersionException -> {
            val id = error.pluginId
            val required = error.requiredVersion
            // Both, because a gate that cannot name what it needs cannot decide whether an
            // available update would clear the floor, and would offer one blind.
            if (id.isNullOrBlank() || required.isNullOrBlank()) {
                null
            } else {
                PluginLoadGate.NeedsNewerHost(
                    pluginId = id,
                    displayName = id,
                    required = required,
                    current = error.currentVersion ?: AppVersion.currentVersionString(),
                )
            }
        }

        is PluginApiLevelException -> {
            val id = error.pluginId
            val required = error.requiredVersion
            if (id.isNullOrBlank() || required.isNullOrBlank()) {
                null
            } else {
                PluginLoadGate.NeedsNewerApi(
                    pluginId = id,
                    displayName = id,
                    required = required,
                    // The api layer is a plugin, so "installed" is the right word and there is no
                    // build-time constant to fall back on the way there is for the app version.
                    current = error.installedVersion ?: "unknown",
                )
            }
        }

        else -> {
            null
        }
    }

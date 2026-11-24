package ai.rever.boss.components.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What the host offers a user whose plugin was refused for a version floor.
 *
 * Written against the incident that motivated it: fluck-browser 1.2.22 shipped requiring BOSS
 * 9.4.23 while 9.4.22 was current, so every host that took the update lost its browser tab and the
 * only trace was one ERROR line in `~/.boss/logs`. Each case below is a position a user was actually
 * in that day.
 */
class PluginLoadGateTest {
    private val hostGate =
        PluginLoadGate.NeedsNewerHost(
            pluginId = "ai.rever.boss.plugin.dynamic.fluckbrowser",
            displayName = "Fluck Browser",
            required = "9.4.23",
            current = "9.4.22",
        )

    private val apiGate =
        PluginLoadGate.NeedsNewerApi(
            pluginId = "ai.rever.boss.plugin.dynamic.fluckbrowser",
            displayName = "Fluck Browser",
            required = "1.0.83",
            current = "1.0.82",
        )

    /** Semver-ish compare, standing in for the loader's own. */
    private val satisfies: (String, String) -> Boolean = { required, candidate ->
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(required)
        val c = parts(candidate)
        (0 until maxOf(r.size, c.size))
            .firstOrNull { i ->
                c.getOrElse(i) { 0 } != r.getOrElse(i) { 0 }
            }?.let { c.getOrElse(it) { 0 } > r.getOrElse(it) { 0 } } ?: true
    }

    private fun remedies(
        gate: PluginLoadGate,
        hostUpdate: String? = null,
        apiUpdate: String? = null,
        revertTo: String? = null,
    ) = remediesFor(gate, RemedyOptions(hostUpdate, apiUpdate, revertTo), satisfies)

    @Test
    fun `a host update that clears the floor is offered first`() {
        val out = remedies(hostGate, hostUpdate = "9.4.23", revertTo = "1.2.21")
        assertEquals(PluginLoadRemedy.UpdateHost("9.4.23"), out.first())
        // Going forward beats going back when both are possible, but going back stays on offer -
        // a user mid-task should not have to restart the app to get their browser back.
        assertEquals(PluginLoadRemedy.RevertPlugin("1.2.21"), out.last())
    }

    @Test
    fun `a host update that does NOT clear the floor is not offered`() {
        // The trap this exists for: an available update is not automatically a fix. Offering one
        // that lands below the floor costs the user a download and a restart and leaves the plugin
        // exactly as missing as before.
        val out = remedies(hostGate, hostUpdate = "9.4.22", revertTo = "1.2.21")
        assertEquals(listOf(PluginLoadRemedy.RevertPlugin("1.2.21")), out)
    }

    @Test
    fun `with no update published, reverting is the whole answer`() {
        // The actual position on the day: 9.4.23 did not exist yet, because the plugin released
        // ahead of the host it required.
        val out = remedies(hostGate, hostUpdate = null, revertTo = "1.2.21")
        assertEquals(listOf(PluginLoadRemedy.RevertPlugin("1.2.21")), out)
    }

    @Test
    fun `with nothing published and nothing kept, say why rather than showing an empty dialog`() {
        val out = remedies(hostGate)
        val nothing = assertIs<PluginLoadRemedy.NothingAvailable>(out.single())
        assertTrue(nothing.reason.contains("9.4.23"), "the reason does not name what is needed")
        assertTrue(nothing.reason.contains("9.4.22"), "the reason does not name what is installed")
    }

    @Test
    fun `an api floor offers an api update, not an application update`() {
        // Deliberately not conflated with the host case. The api layer is itself a hot-swappable
        // plugin, so this is resolvable in seconds without a restart - sending the user to download
        // a whole application update for it would be wrong even when one exists.
        val out = remedies(apiGate, hostUpdate = "9.9.9", apiUpdate = "1.0.83")
        assertEquals(listOf<PluginLoadRemedy>(PluginLoadRemedy.UpdateApi("1.0.83")), out)
    }

    @Test
    fun `an api update below the floor is not offered either`() {
        val out = remedies(apiGate, apiUpdate = "1.0.82", revertTo = "1.2.21")
        assertEquals(listOf(PluginLoadRemedy.RevertPlugin("1.2.21")), out)
    }

    @Test
    fun `an equal version satisfies the floor`() {
        // minBossVersion is a floor, not a strict inequality: 9.4.23 satisfies "9.4.23 or later".
        // Reading it as strict would refuse the exact release built to fix the problem.
        assertTrue(satisfies("9.4.23", "9.4.23"))
        val out = remedies(hostGate, hostUpdate = "9.4.23")
        assertEquals(listOf<PluginLoadRemedy>(PluginLoadRemedy.UpdateHost("9.4.23")), out)
    }

    @Test
    fun `a remedy list is never empty`() {
        // The dialog has to render something for every combination, or it is a window with no way
        // out - which is worse than the silent log line this replaces.
        val combos =
            listOf<Triple<String?, String?, String?>>(
                Triple(null, null, null),
                Triple("9.4.23", null, null),
                Triple(null, "1.0.83", null),
                Triple(null, null, "1.2.21"),
                Triple("9.4.23", "1.0.83", "1.2.21"),
            )
        for (gate in listOf(hostGate, apiGate)) {
            for ((h, a, r) in combos) {
                assertTrue(
                    remedies(gate, h, a, r).isNotEmpty(),
                    "no remedy for $gate with host=$h api=$a revert=$r",
                )
            }
        }
    }

    @Test
    fun `a signature refusal is offered the store copy`() {
        val remedies =
            remediesFor(
                gate = signatureGate(),
                options =
                    RemedyOptions(
                        hostUpdate = "9.9.9",
                        apiUpdate = "1.0.99",
                        revertTo = "1.0.0",
                        storeVersion = "1.9.21",
                    ),
                satisfies = { _, _ -> true },
            )
        // ONLY the reinstall, despite a host update, an api update and a kept jar all being
        // available. None of them fixes bytes that do not match their signature, and offering
        // three buttons that cannot work is worse than one that can.
        assertEquals(listOf(PluginLoadRemedy.ReinstallFromStore("1.9.21")), remedies)
    }

    @Test
    fun `a signature refusal is never offered a revert`() {
        // The remedy that "always applies" for a version floor must NOT apply here: the kept jar
        // is a different version, not a correctly-signed copy of this one, so it would swap one
        // unverifiable artifact for another.
        val remedies =
            remediesFor(
                gate = signatureGate(),
                options =
                    RemedyOptions(
                        hostUpdate = null,
                        apiUpdate = null,
                        revertTo = "1.0.0",
                        storeVersion = null,
                    ),
                satisfies = { _, _ -> true },
            )
        assertTrue(
            remedies.none { it is PluginLoadRemedy.RevertPlugin },
            "a revert cannot fix a signature mismatch: $remedies",
        )
    }

    @Test
    fun `an unreachable store says so rather than offering nothing`() {
        val remedies =
            remediesFor(
                gate = signatureGate(),
                options =
                    RemedyOptions(
                        hostUpdate = "9.9.9",
                        apiUpdate = "1.0.99",
                        revertTo = "1.0.0",
                        storeVersion = null,
                    ),
                satisfies = { _, _ -> true },
            )
        val only = assertIs<PluginLoadRemedy.NothingAvailable>(remedies.single())
        assertTrue(only.reason.isNotBlank())
    }

    @Test
    fun `the store copy is offered even at the version already on disk`() {
        // No floor to clear, so `satisfies` is irrelevant - refusing here on a version comparison
        // would decline to replace tampered bytes with the ones the store vouched for.
        val remedies =
            remediesFor(
                gate = signatureGate(),
                options = RemedyOptions(storeVersion = "1.9.20"),
                // Would veto every candidate if it were consulted.
                satisfies = { _, _ -> false },
            )
        assertEquals(listOf(PluginLoadRemedy.ReinstallFromStore("1.9.20")), remedies)
    }

    private fun signatureGate() =
        PluginLoadGate.SignatureRejected(
            pluginId = "ai.rever.boss.plugin.dynamic.pluginmanager",
            displayName = "Toolbox",
            reason = "No trusted key verified the signature",
        )
}

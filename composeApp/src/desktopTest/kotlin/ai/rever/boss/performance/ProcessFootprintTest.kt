package ai.rever.boss.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the ownership rule and the per-platform parsers in [ProcessFootprint].
 *
 * The ownership rule is the part worth testing, because getting it wrong is silent in both
 * directions and neither direction shows up as an exception. Claim too much and the indicator
 * charges BOSS for the user's own workload - measured at 4,414 MB of `claude` CLIs, Python MCP
 * children and a tunnel on the machine this was written on, all of them descendants of the host
 * JVM by way of a terminal tab. Claim too little and the orphaned engine and plugin processes
 * this indicator exists to surface go uncounted, which is the failure that made it necessary.
 */
class ProcessFootprintTest {
    private val hostPid = 57786L
    private val engineDirs = listOf("/Users/dev/.boss/boss-chromium")

    private fun classify(
        commandLine: String,
        pid: Long = 99999L,
    ) = ProcessFootprint.classify(pid, commandLine, hostPid, engineDirs)

    // region ownership

    @Test
    fun `the host JVM is claimed by pid`() {
        assertEquals(ProcessFootprint.Owner.HOST, classify("/Applications/BOSS.app/Contents/MacOS/BOSS", pid = hostPid))
    }

    @Test
    fun `plugin hosts are claimed by their main class`() {
        assertEquals(
            ProcessFootprint.Owner.PLUGIN,
            classify("/usr/bin/java -Xmx2621m -cp boss-plugin-docker-1.0.1.jar ai.rever.boss.PluginProcessMainKt"),
        )
    }

    @Test
    fun `both the engine main process and its helpers are claimed`() {
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify("/Users/dev/.boss/boss-chromium/BOSS.app/Contents/MacOS/BOSS --port=62988 --browsercore"),
        )
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify(
                "/Users/dev/.boss/boss-chromium/BOSS.app/Contents/Frameworks/Chromium Framework.framework/" +
                    "Helpers/BOSS Helper.app/Contents/MacOS/BOSS Helper --type=renderer",
            ),
        )
    }

    /**
     * The reparented-helper case. Ownership is decided by the executable path alone, so a process
     * that has been orphaned to pid 1 and is no longer a descendant of ours is still counted -
     * which is the entire reason this is a command-line match and not a descendant walk.
     */
    @Test
    fun `an engine process orphaned to init is still ours`() {
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify(
                "/Users/dev/.boss/boss-chromium/BOSS.app/Contents/Frameworks/Chromium Framework.framework/A",
                pid = 57827L,
            ),
        )
    }

    /**
     * The 4.4 GB regression. Every one of these was a live descendant of the host JVM when this
     * was written, by way of a shell in a terminal tab. None of them is BOSS's memory, and none
     * of it is memory BOSS could release.
     */
    @Test
    fun `processes the user started in a terminal are not ours`() {
        assertNull(classify("claude --dangerously-skip-permissions"))
        val python = "/opt/homebrew/Cellar/python@3.14/3.14.6/Frameworks/Python.framework/Versions/3.14/bin/python3"
        assertNull(classify(python))
        assertNull(classify("/Users/dev/.bossterm/bin/cloudflared --no-autoupdate tunnel --url http://127.0.0.1:8080"))
        assertNull(classify("/bin/zsh -l"))
        assertNull(classify("/opt/homebrew/bin/uv tool uvx --from git+https://github.com/oraios/serena serena"))
    }

    /**
     * A different BOSS install's engine, or the user's own Chrome, must not be charged to us.
     */
    @Test
    fun `chromium outside our engine directory is not ours`() {
        assertNull(classify("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
        assertNull(classify("/Users/other/.boss/boss-chromium/BOSS.app/Contents/MacOS/BOSS"))
    }

    /**
     * An empty prefix would make `startsWith` true for every process on the machine, so a failed
     * engine-directory lookup would silently claim the entire system as BOSS's footprint. The
     * guard against that is one `isNotEmpty()` call and this is what holds it in place.
     */
    @Test
    fun `an empty engine directory claims nothing`() {
        assertNull(ProcessFootprint.classify(99999L, "/bin/zsh -l", hostPid, listOf("")))
        assertNull(ProcessFootprint.classify(99999L, "/bin/zsh -l", hostPid, emptyList()))
    }

    // endregion

    // region incremental discovery

    /**
     * The cadence exists for a measured reason: on a machine running 1,219 processes, enumerating
     * pids costs 0-2 ms, reading every command line costs ~70 ms, and reading a five-pid delta
     * costs under a millisecond. Reclassifying everything on every tick burnt about 1.5% of a core
     * continuously to draw one status-bar glyph.
     */
    @Test
    fun `a settled machine classifies nothing`() {
        val known = setOf(1L, 2L, 3L)
        assertEquals(emptySet(), ProcessFootprint.pidsToClassify(known, known, fullRescan = false))
    }

    @Test
    fun `only pids that appeared since the last tick are classified`() {
        val live = setOf(1L, 2L, 3L, 99L)
        val known = setOf(1L, 2L, 3L)
        assertEquals(setOf(99L), ProcessFootprint.pidsToClassify(live, known, fullRescan = false))
    }

    @Test
    fun `a full rescan classifies everything regardless of what is known`() {
        val live = setOf(1L, 2L, 3L)
        assertEquals(live, ProcessFootprint.pidsToClassify(live, live, fullRescan = true))
    }

    @Test
    fun `a departed process stops counting on the very next tick`() {
        // A closed tab's renderer must drop out without waiting for any rescan.
        val owned = mapOf(1L to ProcessFootprint.Owner.HOST, 42L to ProcessFootprint.Owner.BROWSER)
        val retained = ProcessFootprint.retainLive(owned, setOf(1L), fullRescan = false)
        assertEquals(mapOf(1L to ProcessFootprint.Owner.HOST), retained)
    }

    @Test
    fun `a full rescan starts from nothing`() {
        val owned = mapOf(1L to ProcessFootprint.Owner.HOST)
        assertEquals(emptyMap(), ProcessFootprint.retainLive(owned, setOf(1L), fullRescan = true))
    }

    // endregion

    // region display gating

    @Test
    fun `sampling follows the indicator on and off screen`() {
        assertEquals(false, FootprintDisplay.isOnScreen)
        FootprintDisplay.setMounted(true)
        assertEquals(true, FootprintDisplay.isOnScreen)
        FootprintDisplay.setMounted(false)
        assertEquals(false, FootprintDisplay.isOnScreen)
    }

    @Test
    fun `a second window closing does not switch sampling off for the first`() {
        FootprintDisplay.setMounted(true)
        FootprintDisplay.setMounted(true)
        FootprintDisplay.setMounted(false)
        assertEquals(true, FootprintDisplay.isOnScreen)
        FootprintDisplay.setMounted(false)
        assertEquals(false, FootprintDisplay.isOnScreen)
    }

    @Test
    fun `an unpaired dispose cannot latch sampling off`() {
        // Compose does not promise a dispose for every mount under all teardown paths. Without the
        // clamp the count goes negative and no later mount can bring it back above zero.
        FootprintDisplay.setMounted(false)
        FootprintDisplay.setMounted(false)
        FootprintDisplay.setMounted(true)
        assertEquals(true, FootprintDisplay.isOnScreen)
        FootprintDisplay.setMounted(false)
        assertEquals(false, FootprintDisplay.isOnScreen)
    }

    // endregion

    // region per-pid detail

    /**
     * The map rides on the reading rather than beside it, so the sums and the detail can never
     * come from different samples - a tab figure larger than the total it is nested inside is
     * exactly what a torn read would look like on screen.
     */
    @Test
    fun `a reading carries the bytes of each owned pid`() {
        val reading = ProcessFootprint.Reading(1L, 2L, 3L, 2, mapOf(42L to 4096L))
        assertEquals(4096L, reading.bytesByPid[42L])
        assertEquals(6L, reading.totalBytes)
    }

    @Test
    fun `an unmeasured pid is absent rather than zero`() {
        // Absent and zero are different claims: one is "not one of ours, or not measured yet",
        // the other would assert a process holding no memory.
        val reading = ProcessFootprint.Reading(1L, 2L, 3L, 1, mapOf(42L to 4096L))
        assertNull(reading.bytesByPid[99L])
    }

    @Test
    fun `an older reading still deserialises without the map`() {
        // Defaulted, so a Reading constructed the old way keeps working.
        assertEquals(emptyMap(), ProcessFootprint.Reading(1L, 2L, 3L, 0).bytesByPid)
    }

    /**
     * The invariant that actually catches a sampler which forgets to carry the map.
     *
     * The three constructor tests above pin the shape but would all pass if `read()` built its
     * `Reading` without the detail, because they construct one by hand. This takes a real
     * reading and holds the two halves against each other: every measured pid lands in exactly
     * one bucket, so the per-pid values must sum to the totals and count to `processCount`.
     *
     * Returns early rather than failing when no reading is available - a machine or CI image
     * where the process query cannot run is not evidence of a bug - so the mutation guard this
     * provides is real locally and best-effort in CI.
     */
    @Test
    fun `a real reading's per-pid detail agrees with its totals`() {
        FootprintDisplay.setMounted(true)
        try {
            val reading = ProcessFootprint.current() ?: return
            assertEquals(reading.processCount, reading.bytesByPid.size)
            assertEquals(reading.totalBytes, reading.bytesByPid.values.sum())
            assertTrue(reading.bytesByPid.isNotEmpty(), "this JVM is running, so at least one pid is ours")
        } finally {
            FootprintDisplay.setMounted(false)
        }
    }

    // endregion

    // region parsers

    @Test
    fun `ps output parses to bytes`() {
        val parsed = ProcessFootprint.parsePsRssOutput("  57786 2597568\n  57824  266240\n")
        assertEquals(mapOf(57786L to 2597568L * 1024, 57824L to 266240L * 1024), parsed)
    }

    @Test
    fun `ps garbage is skipped rather than failing the whole reading`() {
        val parsed = ProcessFootprint.parsePsRssOutput("57786 2597568\nps: bad pid\n\n57824 notanumber\n")
        assertEquals(mapOf(57786L to 2597568L * 1024), parsed)
    }

    @Test
    fun `powershell csv parses past its header and quoting`() {
        val csv = "\"Id\",\"WorkingSet64\"\r\n\"4812\",\"2721513472\"\r\n\"5120\",\"272629760\"\r\n"
        assertEquals(mapOf(4812L to 2721513472L, 5120L to 272629760L), ProcessFootprint.parseWindowsCsv(csv))
    }

    @Test
    fun `proc fields parse by label`() {
        val rollup = "Rss:               12345 kB\nPss:                8192 kB\nShared_Clean:       1024 kB\n"
        assertEquals(8192L, ProcessFootprint.parseProcKb(rollup, "Pss:"))
        assertEquals(12345L, ProcessFootprint.parseProcKb(rollup, "Rss:"))
        assertNull(ProcessFootprint.parseProcKb(rollup, "VmRSS:"))
    }

    @Test
    fun `a failed query yields no entries rather than zeroes`() {
        assertEquals(emptyMap(), ProcessFootprint.parsePsRssOutput(null))
        assertEquals(emptyMap(), ProcessFootprint.parseWindowsCsv(null))
    }

    // endregion
}

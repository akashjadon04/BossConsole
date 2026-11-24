package ai.rever.boss

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The predicate behind [WindowsArm64SourceIsolationTest], tested directly so a refactor of
 * the file walk can't quietly turn the guard into something that always passes. The walk
 * itself is what makes that guard hard to trust; this is the part worth pinning.
 */
class WindowsArm64ImportPredicateTest {
    private val unavailable = listOf("ai.rever.boss.kernel.", "ai.rever.boss.ipc.")

    private fun check(vararg lines: String) =
        WindowsArm64SourceIsolationTest.importsUnavailablePackage(
            lines = lines.toList(),
            unavailable = unavailable,
        )

    @Test
    fun `an import of an unavailable package is an offence`() {
        assertTrue(check("package ai.rever.boss", "import ai.rever.boss.kernel.KernelBootstrap"))
        assertTrue(check("import ai.rever.boss.ipc.BossIpcClient"))
    }

    @Test
    fun `an indented import still counts`() {
        assertTrue(check("    import ai.rever.boss.kernel.KernelBootstrap"))
    }

    @Test
    fun `imports of available packages are fine`() {
        assertFalse(check("import ai.rever.boss.config.SelfHealingSettingsManager"))
        assertFalse(check("import ai.rever.boss.plugin.PluginStoreSetup"))
    }

    @Test
    fun `a package whose name merely starts the same is not an offence`() {
        // ai.rever.boss.kernelutils would match a naive contains() on "ai.rever.boss.kernel"
        assertFalse(check("import ai.rever.boss.kernelutils.Helper"))
    }

    @Test
    fun `mentioning the package outside an import does not count`() {
        // Class.forName is the sanctioned way to reach the kernel from surviving source.
        assertFalse(check("""    val cls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")"""))
        assertFalse(check(" * Reaches ai.rever.boss.kernel.KernelBootstrap reflectively."))
    }

    @Test
    fun `declaring the package is not importing it`() {
        assertFalse(check("package ai.rever.boss.kernel"))
    }

    @Test
    fun `no lines means nothing to report`() {
        assertFalse(check())
    }
}

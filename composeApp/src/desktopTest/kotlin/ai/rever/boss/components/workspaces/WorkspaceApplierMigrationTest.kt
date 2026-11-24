package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The restore half of the working-directory change, which is the half that touches everyone
 * who already uses BOSS.
 *
 * Layouts on disk were written before `~/BossProjects` existed as a default: a no-project
 * terminal resolved to the home directory and `WorkspaceExtractor` persisted that literal path.
 * Honouring it on restore would put the terminal back in `~` on every launch, and `persisted()`
 * would write it straight back out - `~` is not the default it compares against - so the macOS
 * permission prompts this change removes would return and stay.
 * `DefaultWorkingDirectory.restored` reads a stored home directory as "not set" so the applier
 * re-resolves it. These tests pin that the applier actually calls it, which the pure-function
 * tests in `DefaultWorkingDirectoryTest` cannot.
 *
 * A project is selected throughout, deliberately: it makes the assertions exact - the terminal
 * must come back pointing at the project - and it keeps `resolve()` off its no-project branch,
 * which would create `~/BossProjects` on the machine running the suite.
 */
class WorkspaceApplierMigrationTest {
    private val projectPath = "/tmp/boss-applier-test-project"

    /** Minimal stand-in; the applier only builds TabInfo, it never renders the component. */
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `a saved home-directory terminal comes back in the project, not the home directory`() {
        val home = System.getProperty("user.home")

        val restored = applyTerminal(storedWorkingDirectory = home)

        assertEquals(
            projectPath,
            restored.workingDirectory,
            "a layout written before this change carries a literal home path; honouring it would " +
                "restore the problem on every launch",
        )
    }

    /** The other half: a real directory the user chose is not touched by the migration. */
    @Test
    fun `a saved project directory is restored as it was`() {
        val restored = applyTerminal(storedWorkingDirectory = "/tmp/boss-applier-test-elsewhere")

        assertEquals("/tmp/boss-applier-test-elsewhere", restored.workingDirectory)
    }

    /** No stored directory has always meant "wherever the project is". Unchanged. */
    @Test
    fun `no saved directory follows the project`() {
        val restored = applyTerminal(storedWorkingDirectory = null)

        assertEquals(projectPath, restored.workingDirectory)
    }

    /**
     * A blank stored directory is absent, not a path. It is neither null nor the home
     * directory, so an equality-only migration handed `""` to the tab, and the terminal
     * service's own `ifBlank { user.home }` then started it in the home directory - exempting
     * a slice of exactly the population the migration is for, and one no later save repairs.
     */
    @Test
    fun `a blank saved directory follows the project rather than reaching the terminal`() {
        assertEquals(projectPath, applyTerminal(storedWorkingDirectory = "").workingDirectory)
        assertEquals(projectPath, applyTerminal(storedWorkingDirectory = "   ").workingDirectory)
    }

    /**
     * Applies a one-terminal workspace to a window with [projectPath] selected and returns the
     * terminal that came back.
     *
     * `restoreProject = false` so the window's own selection stands - the workspace records no
     * project of its own here.
     */
    private fun applyTerminal(storedWorkingDirectory: String?): TerminalTabInfo {
        val splitViewState = SplitViewState(tabRegistry, windowId = "applier-test-window")
        val windowProjectState =
            WindowProjectState(windowId = "applier-test-window").apply {
                selectProject(Project(name = "proj", path = projectPath, lastOpened = 0L))
            }

        val workspace =
            LayoutWorkspace(
                id = "applier-test",
                name = "Applier test",
                description = "One terminal",
                layout =
                    SinglePanel(
                        PanelConfig(
                            id = "main",
                            tabs =
                                listOf(
                                    TabConfig(
                                        type = "terminal",
                                        title = "Term",
                                        workingDirectory = storedWorkingDirectory,
                                    ),
                                ),
                        ),
                    ),
            )

        runBlocking { applyWorkspace(workspace, splitViewState, windowProjectState, restoreProject = false) }

        val tabs =
            splitViewState
                .getAllPanels()
                .flatMap { it.tabsComponent.tabsState.value.tabs }
        assertEquals(1, tabs.size, "the workspace declares exactly one tab")
        return tabs.single() as TerminalTabInfo
    }
}

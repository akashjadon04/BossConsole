package ai.rever.boss.crash

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What [CrashReportDialog] says, and whether pressing Escape does what the button
 * says it does.
 *
 * The wording is not cosmetic here. "Don't Send" was accurate while every exit
 * terminated: declining to report and losing the session were the same act. Now
 * that a plugin crash is survivable the same button *keeps* the session, and a
 * user reading "Don't Send" has no way to know that - which is how someone ends
 * up reaching for "Clean Data & Restart" instead and wipes their install over a
 * bad menu handler. That button is therefore absent from this dialog entirely.
 */
class CrashReportDialogRecoveryTest {
    @get:Rule
    val rule = createComposeRule()

    private val dismissals = mutableListOf<String>()

    @Composable
    private fun Dialog(
        recoverablePluginId: String?,
        offerCleanAndRestart: Boolean = true,
    ) {
        CrashReportDialog(
            crashReport = report(recoverablePluginId),
            recoverablePluginId = recoverablePluginId,
            onDismiss = { dismissals.add("dismiss") },
            onSubmit = { _, _ -> dismissals.add("submit") },
            onCleanAndRestart = if (offerCleanAndRestart) ({ dismissals.add("clean") }) else null,
        )
    }

    @Test
    fun `a recoverable plugin crash offers to continue without the plugin`() {
        rule.setContent { Dialog(recoverablePluginId = PLUGIN) }

        rule.onNodeWithText(CONTINUE_WITHOUT_PLUGIN_LABEL).assertIsDisplayed()
        rule.onNodeWithText(PLUGIN, substring = true).assertIsDisplayed()
    }

    @Test
    fun `a recoverable plugin crash never offers to clean the data directory`() {
        // offerCleanAndRestart = TRUE on purpose. The production caller passes null
        // here, but asserting against that combination proved nothing about the
        // dialog - it only re-stated that null renders nothing. Passing a non-null
        // callback is the case that would have rendered the button before the
        // disposition gate was added, so this is the assertion with teeth.
        rule.setContent { Dialog(recoverablePluginId = PLUGIN, offerCleanAndRestart = true) }

        rule.onNodeWithText("Clean Data & Restart").assertDoesNotExist()
        rule.onNodeWithText(DONT_SEND_LABEL).assertDoesNotExist()
    }

    @Test
    fun `a fatal host crash keeps the original wording and the escape hatch`() {
        rule.setContent { Dialog(recoverablePluginId = null) }

        rule.onNodeWithText(DONT_SEND_LABEL).assertIsDisplayed()
        rule.onNodeWithText("Clean Data & Restart").assertIsDisplayed()
        rule.onNodeWithText(CONTINUE_WITHOUT_PLUGIN_LABEL).assertDoesNotExist()
    }

    @Test
    fun `escape does the same thing the visible button does`() {
        rule.setContent { Dialog(recoverablePluginId = PLUGIN) }

        rule.onNodeWithText(CONTINUE_WITHOUT_PLUGIN_LABEL).performClick()
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        // Two invocations of the same callback, not one of each of two. Escape
        // needs the dialog to own focus to reach onKeyEvent at all, which is why
        // the dialog requests it - before that, Escape worked or not depending on
        // whether the notes field happened to be focused.
        assertEquals(listOf("dismiss", "dismiss"), dismissals)
    }

    private fun report(pluginId: String?) =
        CrashReport(
            signature = "sig",
            exceptionType = "IllegalStateException",
            exceptionMessage = "plugin action boom",
            stackTrace = "at plugin.Boom.invoke",
            systemInfo =
                SystemInfo(
                    osName = "TestOS",
                    osVersion = "1",
                    osArch = "test",
                    javaVersion = "21",
                    javaVendor = "test",
                    heapUsedMB = 1,
                    heapMaxMB = 2,
                    nonHeapUsedMB = 1,
                    availableProcessors = 1,
                ),
            appInfo = AppInfo(version = "0.0.0", platform = "macOS", isDebug = true),
            timestamp = 0L,
            pluginId = pluginId,
        )

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
    }
}

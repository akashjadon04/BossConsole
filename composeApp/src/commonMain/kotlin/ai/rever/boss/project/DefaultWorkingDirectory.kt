package ai.rever.boss.project

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException

/**
 * Where BOSS works when no project is selected.
 *
 * Everything that needed a directory and had no project used to reach for
 * `System.getProperty("user.home")`: the `{projectPath}` placeholder in workspaces and split
 * templates, and every terminal opened before a project is picked (a null working directory
 * lands the shell in `~`). On macOS that is the worst available default - the first `ls`, or
 * any agent started in `~`, walks into `~/Desktop`, `~/Documents` and `~/Downloads`, and TCC
 * puts a permission prompt in front of the user for each one, for a directory they never
 * asked BOSS to open.
 *
 * `~/BossProjects` is the directory BOSS already owns: [ProjectCreationService] creates
 * projects there by default and the clone dialog clones there. Nothing macOS guards lives
 * inside it, so starting there asks for nothing.
 *
 * This does **not** select a project. "No Project" stays "No Project" - what changes is only
 * which directory the no-project case resolves to.
 */
object DefaultWorkingDirectory {
    private val logger = BossLogger.forComponent("DefaultWorkingDirectory")

    /**
     * The default working directory, created if it does not exist yet.
     *
     * Falls back - to `~/.boss`, then the home directory - when `~/BossProjects` cannot be
     * created, a file already occupies that name, or it exists but is not writable. Handing
     * back a path that does not exist or cannot be written to would be worse than the problem
     * this fixes: a terminal spawned with such a working directory fails to start, where the
     * old default only started somewhere noisy. See [fallbackDirectory].
     *
     * Deliberately not cached. It is called when a tab is created, never in a loop, and a
     * cached path would go on being handed out after the user moved or deleted the directory.
     */
    fun ensureDefaultDirectory(): String = ensureDefaultDirectory(::ensureDirectory)

    /**
     * [ensureDefaultDirectory] against a supplied creator, so the fallback composition is testable without a
     * machine on which creating `~/BossProjects` actually fails.
     */
    internal fun ensureDefaultDirectory(ensure: (File) -> String?): String {
        val target = File(nominalPath())
        return ensure(target) ?: fallbackDirectory(target)
    }

    /**
     * Where the default working directory *would* be, without creating it or touching the
     * filesystem at all.
     *
     * For callers that only need the string to compare against - [persisted], through
     * `WorkspaceExtractor`. Two reasons they must not use [ensureDefaultDirectory] instead:
     *
     * - **Cost and position.** Extraction runs from the auto-save `snapshotFlow`, whose
     *   producer re-runs on the composition thread whenever any tab's title, url or working
     *   directory changes, and from the Last Session teardown, which can be the shutdown-hook
     *   thread. A `createDirectories` syscall per browser-title update is the kind of blocking
     *   I/O `docs/THREADING.md` rules out, and its warn-on-failure would repeat just as often.
     * - **Stability.** [ensureDefaultDirectory] answers with a *fallback* directory when
     *   creation fails. A transient failure at extract time would make [persisted] compare a
     *   terminal's real `~/BossProjects` against that fallback, find them different, and
     *   freeze the resolved default into the saved layout - precisely what [persisted] exists
     *   to prevent. This cannot fail, so *that* direction is closed: which string the
     *   comparison uses no longer depends on filesystem state at extract time.
     *
     * The mirror case is not closed *here*, and only half needs to be: if creation failed when
     * the *terminal* was created, its working directory is a fallback, which differs from this
     * and so is persisted verbatim. Where that fallback was the home directory, [restored]
     * reads it as absent on the way back in and the layout self-heals on the next restore.
     * Where it was `~/.boss`, it does not - that path is stored and restored like any other.
     *
     * Through `File` on both sides, so the separator normalization matches what [resolve]
     * handed the tab.
     */
    fun nominalPath(): String = File(ProjectCreationService.getDefaultProjectsDirectory()).path

    /**
     * [projectPath] when a project is selected, [ensureDefaultDirectory] otherwise.
     *
     * **Touches the filesystem on the no-project branch** - a `stat`, and a create the first
     * time. Free when a project is selected, since [ensureDefaultDirectory] is never reached. Call it off the
     * main thread where that is possible: `applyWorkspace` does. The tab-creation handlers do
     * not, because they are not suspending and the cost is one `stat` in the steady state
     * (the startup warm-up in `main` has normally done the create already) - but on a network
     * home directory, a Windows roaming profile or a macOS network account, an unresponsive
     * volume stalls the frame. That is the known cost of this being uncached; see [ensureDefaultDirectory].
     */
    fun resolve(projectPath: String?): String = resolve(projectPath, ::ensureDefaultDirectory)

    /**
     * [projectPath] if it names a selected project, null if it stands for "no project".
     *
     * Blank counts as absent: `WindowProjectState` models "no project" as an empty path, and a
     * hand-edited workspace file can carry a whitespace-only one.
     */
    internal fun selectedOrNull(projectPath: String?): String? = projectPath?.takeIf { it.isNotBlank() }

    /**
     * [resolve] against a supplied default, so tests can exercise the fallback without calling
     * [ensureDefaultDirectory] - which creates a directory under the real home directory of whatever machine
     * runs them.
     */
    internal fun resolve(
        projectPath: String?,
        default: () -> String,
    ): String = selectedOrNull(projectPath) ?: default()

    /**
     * What to write to a saved workspace for a terminal whose working directory is
     * [workingDirectory], given [default] - the default directory's name, from [nominalPath] or
     * equivalently [ensureDefaultDirectory].
     *
     * Null means "re-resolve on restore", and that is the answer for a terminal sitting in the
     * default directory. Before this class, a terminal opened with no project carried a null
     * working directory and `WorkspaceApplier` substituted whatever project was selected when
     * the workspace was applied *later*. Persisting the resolved `~/BossProjects` verbatim
     * would freeze that decision: re-applying the layout with a project selected would open
     * the terminal in the projects folder instead of the project.
     *
     * Two consequences of keying on the path rather than on "was this resolved from no
     * project". Both sides of the comparison must have gone through `File` - [nominalPath] and
     * [ensureDefaultDirectory] both have, and [resolve] is what put the tab's value there; a [default] reaching
     * this by some route that skips that normalization never matches on Windows. And a terminal the user
     * deliberately pointed at `~/BossProjects` is indistinguishable from a default one: with
     * no project selected restore lands back in the projects folder, which is where it was
     * pointed, but *with* a project selected the null re-resolves to the project instead. That
     * second case loses the user's choice. Accepted rather than fixed - telling the two apart
     * needs a flag on `TabConfig`, i.e. a workspace format change, to serve a terminal
     * deliberately opened in the projects folder itself.
     */
    internal fun persisted(
        workingDirectory: String?,
        default: String,
    ): String? = selectedOrNull(workingDirectory)?.takeIf { it != default }

    /**
     * A saved workspace's terminal working directory, read back with the home directory
     * treated as "not set".
     *
     * Without this the fix does not reach anyone who already uses BOSS. Saved layouts on disk
     * were written by the code this replaces: a no-project terminal resolved to `~`, carried a
     * *non-null* `~`, and `WorkspaceExtractor` persisted it verbatim. So Last Session would go
     * on restoring terminals into the home directory after the upgrade - and [persisted] would
     * go on writing `~` back out, because `~` is not the default it compares against. The TCC
     * prompts would return on the first restore and never stop, for exactly the people this
     * change is for.
     *
     * Reading `~` as absent lets [WorkspaceApplier] re-resolve it to `~/BossProjects`; the next
     * auto-save then extracts that, [persisted] nulls it, and the layout is repaired. It is a
     * migration by value rather than a versioned one, because `TabConfig` carries no version
     * and adding one to fix this would be a workspace format change.
     *
     * The cost is a terminal someone deliberately parked in their home directory, which
     * re-resolves to the projects folder once. That is the trade this whole change is: the
     * home directory is the place BOSS should not be working in. Note the predicate is "the
     * stored value is the home directory", whoever wrote it - a plugin that called
     * `addTerminalTab(workingDirectory = <home>)` and had that layout saved is relocated by the
     * same rule. So this is not *only* a migration, though that is what it is for.
     *
     * Best effort, and exact-match by design: a stored path differing by a trailing separator,
     * or a home reached through a symlink (`/Users/x` against a resolved
     * `/System/Volumes/Data/Users/x`), is not recognised and goes on restoring into `~`.
     * Canonicalising would put filesystem I/O on the restore path for every terminal tab.
     *
     * Logged when it fires, so a terminal that moved once is explainable from a bug report
     * rather than looking like the tab losing its directory.
     */
    internal fun restored(
        workingDirectory: String?,
        home: String? = System.getProperty("user.home"),
    ): String? {
        // Blank counts as absent here too, the same rule selectedOrNull applies to a project
        // path. A stored "" is neither null nor equal to home, so returning it verbatim left
        // the applier's `?: resolvedProjectPath` unreached, built the tab with "", and
        // TerminalServiceImpl's own `ifBlank { user.home }` put the terminal in the home
        // directory - the exact outcome this exists to remove, and one persisted() would not
        // repair either, so it survived every launch.
        val selected = selectedOrNull(workingDirectory)
        // `selected != null` matters: with no `user.home` at all, [home] is null too, and a
        // blank stored value would otherwise log a relocation that is really just "absent".
        if (selected != null && selected == home) {
            logger.info(
                LogCategory.WORKSPACE,
                "Restoring a saved home-directory terminal into the default projects directory",
                mapOf("path" to selected),
            )
            return null
        }
        return selected
    }

    /**
     * [target]'s path once it is known to be a directory, or null if it cannot be made one.
     *
     * Split out so tests can exercise both outcomes against a temporary directory rather than
     * creating `~/BossProjects` on whatever machine runs them.
     */
    internal fun ensureDirectory(target: File): String? {
        val failure =
            try {
                // isDirectory first: that is the steady state and the only branch the UI
                // thread normally takes, and it costs two stats with no create and no
                // exception. Short-circuiting the *create* on a true answer is race-free - the
                // directory being there is the outcome the create was for.
                //
                // createDirectories, not mkdirs(). Three callers create this same path - the
                // startup warm-up in main(), the first window's resolve() and
                // validateProjectLocation - and mkdirs() returns false when another of them
                // won the race, which would send this caller to the home-directory fallback
                // on exactly the cold first launch this exists to fix. createDirectories
                // succeeds when the directory is already there, so losing the race between
                // the check above and this line is not a failure either.
                if (!target.isDirectory) Files.createDirectories(target.toPath())

                // Writability is checked, not assumed, and after the create rather than
                // instead of it: createDirectories *succeeds* on a directory that already
                // exists, so an unwritable BossProjects - wrong ownership after a restore
                // from backup, a mount with different perms - would otherwise be reported
                // usable. Every terminal would then start somewhere the shell cannot create
                // a file, and the home-directory fallback, which exists to guarantee "a path
                // a terminal can start in", would never fire. The old behaviour was `~`,
                // writable by construction, so that would be a failure mode this change
                // introduced rather than inherited.
                //
                // Files.isWritable, not File.canWrite: on Windows the JDK short-circuits
                // canWrite() to true for anything with FILE_ATTRIBUTE_DIRECTORY set, so it
                // cannot see the ACL case this check is for. Files.isWritable consults the ACL.
                if (Files.isWritable(target.toPath())) {
                    null
                } else {
                    IOException("Projects directory is not writable")
                }
            } catch (e: IOException) {
                // Includes FileAlreadyExistsException, which createDirectories raises only for
                // a non-directory at the path - a *file* named BossProjects.
                e
            } catch (e: SecurityException) {
                e
            } catch (e: InvalidPathException) {
                // Unchecked, from toPath(). Vanishingly unlikely for a home-relative path, but
                // it would otherwise escape past the fallback to the caller.
                e
            }

        // Every failure that happens in the field - a read-only home, a quota, a file at the
        // path - lands here, and the user meets it as either the home-directory prompts this
        // exists to remove or a refusal in the New Project wizard. Silence would leave nothing
        // in the log to explain either. The message names only the failure, not what the
        // caller does about it: validateProjectLocation does not fall back to anything.
        failure?.let {
            logger.warn(
                LogCategory.FILE,
                "Cannot create the projects directory",
                mapOf("path" to target.path),
                error = it,
            )
        }

        return if (failure == null) target.path else null
    }

    /**
     * Where to work when `~/BossProjects` cannot be used.
     *
     * `~/.boss` first, and only then the home directory. The two failures that actually reach
     * here are a *file* named BossProjects and an unwritable one, and in both `~/.boss` is
     * fine: BOSS owns it, other subsystems create it already, and macOS does not guard it. So
     * the fallback keeps the point of this class rather than surrendering it - falling
     * straight to `~` would hand back the TCC prompts at the first hurdle. It is not a
     * projects folder, which is why it is the fallback and not the default.
     *
     * The home directory remains the last resort, and [target] itself if there is not even
     * one. That last branch is where this class breaks its own contract of "a path a terminal
     * can start in": with no `user.home`, `getDefaultProjectsDirectory()` yields the *relative*
     * `BossProjects`, which resolves against the process working directory - `/` for a packaged
     * `.app`. Nothing is gained by inventing a better guess; a JVM defining no home directory
     * is a broken environment, and the previous code produced a literal `null/BossProjects` in
     * the same situation.
     */
    private fun fallbackDirectory(target: File): String {
        val bossDir = BossDirectories.rootDir
        // Checked, not created: rootDir's own accessor already tries, and this is the path
        // where creating things in the home directory has just failed.
        if (bossDir.isDirectory && Files.isWritable(bossDir.toPath())) return bossDir.path

        return System.getProperty("user.home") ?: target.path
    }
}

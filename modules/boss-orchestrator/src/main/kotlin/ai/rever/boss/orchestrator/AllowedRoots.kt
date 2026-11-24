package ai.rever.boss.orchestrator

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The directory roots a path from outside the host must resolve inside.
 *
 * The orchestrator receives filesystem paths over IPC — `ProcessManifest.sourceFiles` is
 * written by the process being diagnosed, not by the host — and hands the contents it reads
 * to [AiRepairClient], which posts them to a third-party model. Every such path is judged
 * here first, so that the set of files that can leave the machine is the host's decision.
 *
 * ### What "resolves inside" means
 *
 * A textual `startsWith` on the path a caller sent answers the wrong question: `..` walks up
 * out of the root, a symlink inside the root points anywhere, and `/tmp/project` is a string
 * prefix of the unrelated `/tmp/project-notes`. So [resolve]:
 *
 * - canonicalizes the **deepest existing ancestor** of the candidate (`toRealPath`), which
 *   resolves every symlink and `..` in the part of the path that exists, and re-appends the
 *   absent tail. A path that does not exist yet is still judged, on where it *would* land;
 * - refuses any tail component that exists, which is two cases in one: a **symlink with a
 *   missing target**, the one thing below the deepest resolvable ancestor that exists and can
 *   still name somewhere else entirely; and a `..` that climbs above that ancestor, because
 *   the parent of a directory that exists also exists. So a path that walks up out of the
 *   root is refused rather than compared;
 * - compares by path component ([Path.startsWith]), not by string prefix.
 *
 * ### The roots themselves
 *
 * [none] grants nothing, and so does a root that cannot be resolved to a directory — an
 * unusable root must narrow the reach rather than widen it. A filesystem root (`/`, `C:\`)
 * is also refused as a root, because confining to it confines nothing; a caller that passes
 * one gets a warning and no reach, not silent unlimited reach.
 *
 * ### What this is not
 *
 * Not a sandbox: anything inside a granted root is readable. It narrows what an IPC caller
 * can name to the directory the host meant, which for this module is the project root. It
 * also does not close the gap between the check and the read — the file is opened again by
 * path afterwards, and a component could change in between. Both are recorded rather than
 * claimed to be solved.
 */
class AllowedRoots private constructor(
    private val roots: List<Path>,
) {
    /**
     * The path [candidate] resolves to, or `null` when it lands outside every root, cannot be
     * resolved at all, or there are no roots.
     */
    fun resolve(candidate: File): File? {
        val target = if (roots.isEmpty()) null else resolvedTarget(candidate.toPath())
        return target?.takeIf { path -> roots.any(path::startsWith) }?.toFile()
    }

    /** The canonical roots, for a startup or refusal log line. */
    fun rootPaths(): List<String> = roots.map(Path::toString)

    /** Where [candidate] actually lands, following every symlink and `..` in it. */
    private fun resolvedTarget(candidate: Path): Path? {
        val absolute = absoluteOrNull(candidate) ?: return null
        return realPathOrNull(absolute) ?: resolveThroughAbsentTail(absolute)
    }

    /**
     * The same answer for a path that does not exist yet: the deepest ancestor that can be
     * resolved, with the components below it appended back on.
     */
    private fun resolveThroughAbsentTail(absolute: Path): Path? {
        val resolvedAncestor =
            generateSequence(absolute.parent) { it.parent }
                .firstNotNullOfOrNull { ancestor -> realPathOrNull(ancestor)?.let { it to ancestor } }
                ?: return null
        val (real, ancestor) = resolvedAncestor
        return appendAbsentTail(real, ancestor.relativize(absolute))
    }

    /**
     * Appends [tail] to [real], refusing any component of it that exists.
     *
     * Every component of [tail] is below the deepest ancestor that could be resolved, so all of
     * them should be absent. Two things can exist there, and neither may be appended blindly:
     * a symlink whose target is missing, which cannot be resolved and can name anywhere; and a
     * `..` that climbs back above [real], which exists because the parent of a directory that
     * exists also exists. [Path.relativize] has already collapsed every `..` that stays inside
     * the ancestor, so one that survives into [tail] is one that leaves it.
     */
    private fun appendAbsentTail(
        real: Path,
        tail: Path,
    ): Path? {
        var resolved = real
        for (name in tail) {
            resolved = resolved.resolve(name)
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) return null
        }
        // Normalize even though [Path.relativize] has already collapsed the `..` that could
        // reach here: that collapsing is JDK-version-dependent behaviour, and this is the one
        // step that makes the containment check below true of the path itself rather than
        // true by delegation. `resolve` does not normalize, so without it a surviving `..`
        // would still satisfy `startsWith` component-wise. Nothing writes through this path
        // today — the caller only reads, and the OS will not traverse an absent component —
        // but the API judges paths that do not exist yet, and the next caller may create one.
        return resolved.normalize()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AllowedRoots::class.java)

        /** No roots: every path is refused. */
        fun none(): AllowedRoots = AllowedRoots(emptyList())

        /** Confinement to [candidates]. Each is resolved once here; see the class docs. */
        fun of(vararg candidates: File): AllowedRoots {
            val resolved = LinkedHashSet<Path>()
            for (candidate in candidates) {
                val real = realPathOrNull(candidate.toPath())
                when {
                    real == null || !Files.isDirectory(real) -> {
                        logger.warn("Root {} is not a resolvable directory; it grants nothing", candidate)
                    }

                    real.parent == null -> {
                        logger.warn("Root {} is a filesystem root, which confines nothing; it grants nothing", real)
                    }

                    else -> {
                        resolved.add(real)
                    }
                }
            }
            return AllowedRoots(resolved.toList())
        }

        private fun absoluteOrNull(path: Path): Path? =
            try {
                path.toAbsolutePath()
            } catch (_: Exception) {
                null
            }

        private fun realPathOrNull(path: Path): Path? =
            try {
                path.toRealPath()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
    }
}

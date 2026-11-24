package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.dependency.SemanticVersion

/**
 * True when [installed] satisfies the [required] floor.
 *
 * Fails OPEN on missing or unparseable versions (dev builds, candidates published before the
 * field existed) - the loader's own gate is the backstop. Compares the release core only,
 * ignoring prerelease: a host at 9.2.27-alpha.1 is built from the same source as 9.2.27 and
 * carries the same API, but semver precedence would rank it below the requirement and
 * spuriously gate every exact-version update on prerelease hosts.
 *
 * **Public and top-level because there are now two callers.** It began as
 * `PluginUpdateManager.satisfiesFloor`, gating updates. The home screen's tool grid needs the
 * same question answered about a store row before offering it as an install - a tile that
 * downloads a jar this host cannot load is worse than a plugin the grid never mentions - and a
 * second copy of these five comparisons is exactly the kind of near-duplicate that drifts.
 */
// Guard clauses: the three fail-open cases and the two component comparisons each read as one
// rule, and folding them into a single expression would obscure which case fired.
@Suppress("ReturnCount")
fun satisfiesVersionFloor(
    required: String,
    installed: String,
): Boolean {
    if (required.isBlank() || installed.isBlank()) return true
    val req = SemanticVersion.parse(required) ?: return true
    val cur = SemanticVersion.parse(installed) ?: return true
    if (cur.major != req.major) return cur.major > req.major
    if (cur.minor != req.minor) return cur.minor > req.minor
    return cur.patch >= req.patch
}

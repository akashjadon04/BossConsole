package ai.rever.boss.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts no workflow declares the same `env:` key twice.
 *
 * A duplicate key in a GitHub Actions mapping makes Actions refuse to start the
 * workflow at all: `startup_failure`, zero jobs, and a run whose name is the file
 * path because the `name:` field was never read. It took down release.yml on main
 * and cost two dispatch attempts before anyone could see why.
 *
 * It reached main because **PyYAML accepts duplicate keys silently**, taking the
 * last value - so `yaml.safe_load` on the file returned a valid document and the
 * pre-merge check passed. Every YAML-parses check in this repo has that blind
 * spot; this test is the one that does not.
 *
 * Scoped to `env:` blocks deliberately: that is where the failure happened, and
 * where it will happen again, because an env block is a list of credentials that
 * grows one line at a time and a newcomer cannot see the existing entries without
 * reading past the comment blocks between them.
 */
class WorkflowEnvKeysTest {
    private fun workflowsDir(): File {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        return File(root, ".github/workflows")
    }

    /**
     * Keys declared in every `env:` block of [text], as (block start line, keys).
     *
     * Line-based rather than a YAML parse, for the reason above: the parsers
     * available here are exactly the ones that do not see the problem. A block is
     * the run of `  key: value` lines at one indentation directly under an `env:`
     * line; comments and blank lines are skipped, and anything at a shallower
     * indent or starting a list item ends the block.
     */
    private fun envBlocks(text: String): List<Pair<Int, List<String>>> {
        val lines = text.split("\n")
        val blocks = mutableListOf<Pair<Int, List<String>>>()

        lines.forEachIndexed { index, line ->
            val envIndent =
                Regex("""^(\s*)env:\s*$""")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.length ?: return@forEachIndexed
            val keys = mutableListOf<String>()
            var blockIndent: Int? = null

            @Suppress("UNUSED_VARIABLE")
            var cursor = index

            // takeWhile then filter, rather than a loop with several jumps: the
            // block ends at the first line that dedents or starts a list item,
            // and everything after that is a per-line decision.
            val body =
                lines
                    .drop(index + 1)
                    .takeWhile { line ->
                        val trimmed = line.trimStart()
                        val indent = line.length - trimmed.length
                        line.isBlank() ||
                            trimmed.startsWith("#") ||
                            (indent > envIndent && !trimmed.startsWith("- "))
                    }.filterNot { it.isBlank() || it.trimStart().startsWith("#") }

            body.forEach { current ->
                val trimmed = current.trimStart()
                val indent = current.length - trimmed.length
                if (blockIndent == null) blockIndent = indent
                if (indent == blockIndent) {
                    Regex("""^([A-Za-z_][A-Za-z0-9_.-]*)\s*:""")
                        .find(trimmed)
                        ?.groupValues
                        ?.get(1)
                        ?.let(keys::add)
                }
            }
            if (keys.isNotEmpty()) blocks.add(index + 1 to keys)
        }
        return blocks
    }

    @Test
    fun `no workflow declares an env key twice`() {
        val dir = workflowsDir()
        assertTrue(dir.isDirectory, "no .github/workflows at ${dir.absolutePath}")
        val workflows = dir.listFiles { f -> f.name.endsWith(".yml") || f.name.endsWith(".yaml") }.orEmpty()
        assertTrue(workflows.isNotEmpty(), "no workflow files found")

        val offences =
            workflows.flatMap { file ->
                envBlocks(file.readText()).flatMap { (line, keys) ->
                    keys
                        .groupingBy { it }
                        .eachCount()
                        .filterValues { it > 1 }
                        .map { (key, count) -> "${file.name}:$line declares env key '$key' $count times" }
                }
            }

        assertTrue(
            offences.isEmpty(),
            "A duplicate env key makes Actions refuse to start the workflow (startup_failure, zero jobs). " +
                "PyYAML will not catch it - it takes the last value silently.\n" +
                offences.joinToString("\n"),
        )
    }

    @Test
    fun `the detector actually catches a duplicate`() {
        // Reverse-verified: without this, a detector that silently found nothing
        // would pass forever and read as proof. The shape is the exact one that
        // broke release.yml - two identical keys separated by a comment block.
        val broken =
            """
            name: x
            env:
              GRADLE_OPTS: '-Dx'
              GITHUB_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
              # a comment between them, which is how it went unnoticed
              GITHUB_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
            jobs:
              build:
                runs-on: ubuntu-latest
            """.trimIndent()
        val keys = envBlocks(broken).single().second
        assertEquals(listOf("GRADLE_OPTS", "GITHUB_TOKEN", "GITHUB_TOKEN"), keys)
        assertEquals(2, keys.count { it == "GITHUB_TOKEN" })
    }

    @Test
    fun `a job-level env block is read separately from the workflow one`() {
        // Two blocks, each with its own GITHUB_TOKEN, is legal: a step-level
        // assignment overriding the workflow one is a documented pattern in
        // release.yml. Only a repeat WITHIN one block is the error.
        val legal =
            """
            env:
              GITHUB_TOKEN: a
            jobs:
              build:
                env:
                  GITHUB_TOKEN: b
                steps: []
            """.trimIndent()
        val blocks = envBlocks(legal)
        assertEquals(2, blocks.size, "expected two separate env blocks, got $blocks")
        blocks.forEach { (_, keys) -> assertEquals(keys.distinct(), keys) }
    }
}

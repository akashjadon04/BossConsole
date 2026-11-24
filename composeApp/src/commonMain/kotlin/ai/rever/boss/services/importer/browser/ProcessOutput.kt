package ai.rever.boss.services.importer.browser

import java.util.concurrent.TimeUnit

/** Result of running a short-lived helper process. */
internal data class ProcessOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Run [command] and collect both streams.
 *
 * Reading stdout to EOF and only then reading stderr deadlocks whenever the
 * child writes more to stderr than its pipe buffer holds: the child blocks
 * writing, the parent blocks reading the other stream. It also makes any
 * timeout decorative, because the blocking read happens before `waitFor`.
 *
 * Both streams are therefore drained on their own threads while the parent
 * waits with a real deadline.
 */
internal fun runProcess(
    command: List<String>,
    timeoutSeconds: Long,
): ProcessOutput {
    val process = ProcessBuilder(command).start()

    // StringBuffer, not StringBuilder: the bounded joins below can time out, and
    // reading an unsynchronised builder while a pump thread is still appending
    // can throw or return torn content.
    val out = StringBuffer()
    val err = StringBuffer()
    val outPump = Thread { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
    val errPump = Thread { process.errorStream.bufferedReader().use { err.append(it.readText()) } }
    outPump.isDaemon = true
    errPump.isDaemon = true
    outPump.start()
    errPump.start()

    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(PROCESS_KILL_GRACE_SECONDS, TimeUnit.SECONDS)
    }

    // Bounded joins: the pumps end when the streams close, which destroying the
    // process guarantees, but never hang the caller if that goes wrong.
    outPump.join(PUMP_JOIN_MILLIS)
    errPump.join(PUMP_JOIN_MILLIS)

    return ProcessOutput(
        exitCode = if (finished) process.exitValue() else TIMED_OUT_EXIT_CODE,
        stdout = out.toString(),
        stderr = err.toString(),
    )
}

/** Sentinel exit code for "the process was killed after exceeding its deadline". */
internal const val TIMED_OUT_EXIT_CODE = -1

private const val PROCESS_KILL_GRACE_SECONDS = 5L
private const val PUMP_JOIN_MILLIS = 2_000L

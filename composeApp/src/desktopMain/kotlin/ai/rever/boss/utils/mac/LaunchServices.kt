package ai.rever.boss.utils.mac

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef

/**
 * The three Launch Services calls BOSS needs to read and write the OS's
 * "which app opens this" answers, bound directly instead of shelled out to
 * `swift`.
 *
 * **Why not the Swift scripts it replaces.** The previous implementation wrote a
 * temporary `.swift` file and ran `swift <file>` for every query and every set.
 * That needs Xcode or the Command Line Tools installed: on a machine with
 * neither - which is most machines that are not a developer's own - the Settings
 * card could only ever say "Error checking status" and the button could not work
 * at all. It also paid a Swift front-end compile (hundreds of ms to seconds) per
 * call, which was tolerable for one browser check and is not for a screen that
 * reports a status per file-type category.
 *
 * **Deprecated but not gone.** `LSSetDefaultHandlerForURLScheme` and friends are
 * marked deprecated since macOS 10.15 in favour of `NSWorkspace`, whose
 * replacements are Objective-C instance methods on a shared object - reachable
 * from JNA only through the Objective-C runtime, which is a substantially larger
 * and more fragile surface than four C functions. The C functions are still
 * exported and still work (verified against macOS 26); when they are finally
 * removed, [isAvailable] starts answering false and callers fall back rather
 * than crash.
 *
 * **Memory.** Every `LSCopy*` returns a +1 CFString that this object releases
 * before handing back a Kotlin `String`, and every CFString it creates for an
 * argument is released in a `finally`. Getting that wrong leaks a small
 * allocation per call on a path the Settings screen can drive repeatedly.
 */
internal object LaunchServices {
    private val logger = BossLogger.forComponent("LaunchServices")

    /**
     * `kLSRolesAll`. Declared as `-1` rather than `0xFFFFFFFF` because the
     * parameter is a 32-bit `LSRolesMask` and JNA maps Kotlin `Int` to it
     * directly; the two have the same bit pattern.
     */
    private const val ROLES_ALL = -1

    /** `noErr`. Anything else from an `LSSet*` means the OS declined. */
    private const val NO_ERR = 0

    @Suppress("FunctionNaming", "FunctionName")
    private interface CoreServices : Library {
        fun LSCopyDefaultHandlerForURLScheme(scheme: CFStringRef): Pointer?

        fun LSSetDefaultHandlerForURLScheme(
            scheme: CFStringRef,
            bundleId: CFStringRef,
        ): Int

        fun LSCopyDefaultRoleHandlerForContentType(
            contentType: CFStringRef,
            roles: Int,
        ): Pointer?

        fun LSSetDefaultRoleHandlerForContentType(
            contentType: CFStringRef,
            roles: Int,
            bundleId: CFStringRef,
        ): Int
    }

    /**
     * The loaded framework, or null when it could not be bound.
     *
     * Resolved once and lazily: `Native.load` is not free, and on a
     * non-macOS host it throws - so this must not run at class-init time on
     * Windows or Linux merely because something imported the file.
     */
    private val coreServices: CoreServices? by lazy {
        try {
            Native.load("CoreServices", CoreServices::class.java)
        } catch (e: UnsatisfiedLinkError) {
            logger.warn(LogCategory.SYSTEM, "Could not bind CoreServices; Launch Services calls unavailable", error = e)
            null
        } catch (e: NoClassDefFoundError) {
            // JNA missing from the runtime classpath entirely (a stripped build).
            logger.warn(LogCategory.SYSTEM, "JNA unavailable; Launch Services calls unavailable", error = e)
            null
        } catch (e: Throwable) {
            // isAvailable() is meant to be the gate that cannot itself fail. An
            // unexpected JNA or linker problem must degrade to "unavailable"
            // rather than propagate out of a lazy initialiser and take the
            // Settings screen with it.
            logger.warn(LogCategory.SYSTEM, "Unexpected failure binding CoreServices", error = e)
            null
        }
    }

    /**
     * Whether the native calls can be made at all.
     *
     * Callers use this to choose the fallback path rather than discovering the
     * failure one silent null at a time.
     */
    fun isAvailable(): Boolean = coreServices != null

    /** Bundle id currently registered for [scheme] (for example "http"), or null. */
    fun defaultHandlerForScheme(scheme: String): String? {
        // Hoisted above withCFString: reads unambiguously, and allocates no
        // CFString at all when the framework is not bound.
        val services = coreServices ?: return null
        return withCFString(scheme) { cfScheme ->
            copiedString { services.LSCopyDefaultHandlerForURLScheme(cfScheme) }
        }
    }

    /** Bundle id currently registered for [contentType] (a UTI), or null. */
    fun defaultHandlerForContentType(contentType: String): String? {
        val services = coreServices ?: return null
        return withCFString(contentType) { cfType ->
            copiedString { services.LSCopyDefaultRoleHandlerForContentType(cfType, ROLES_ALL) }
        }
    }

    /**
     * Makes [bundleId] the handler for [scheme]. True when the OS accepted it.
     *
     * A false is not necessarily an error to escalate: the OS refuses when the
     * bundle id is not registered (an app that was never launched from its final
     * location), and the caller's answer to that is to send the user to System
     * Settings, not to retry.
     */
    fun setDefaultHandlerForScheme(
        scheme: String,
        bundleId: String,
    ): Boolean {
        // Hoisted, which also removes a `return@withCFString false` whose label
        // resolved to the inner of two nested `withCFString` calls: correct, but
        // not something a reader should have to work out.
        val services = coreServices ?: return false
        return withCFString(scheme) { cfScheme ->
            withCFString(bundleId) { cfBundle ->
                val status = services.LSSetDefaultHandlerForURLScheme(cfScheme, cfBundle)
                logStatus("scheme", scheme, bundleId, status)
                status == NO_ERR
            }
        }
    }

    /** Makes [bundleId] the handler for the UTI [contentType]. True when the OS accepted it. */
    fun setDefaultHandlerForContentType(
        contentType: String,
        bundleId: String,
    ): Boolean {
        val services = coreServices ?: return false
        return withCFString(contentType) { cfType ->
            withCFString(bundleId) { cfBundle ->
                val status = services.LSSetDefaultRoleHandlerForContentType(cfType, ROLES_ALL, cfBundle)
                logStatus("contentType", contentType, bundleId, status)
                status == NO_ERR
            }
        }
    }

    private fun logStatus(
        kind: String,
        target: String,
        bundleId: String,
        status: Int,
    ) {
        if (status == NO_ERR) {
            logger.debug(
                LogCategory.SYSTEM,
                "Registered default handler",
                mapOf(kind to target, "bundleId" to bundleId),
            )
        } else {
            // OSStatus, not errno: worth recording verbatim, because the values
            // that show up here (-10814 kLSApplicationNotFoundErr in particular)
            // point at a different problem than "the call failed".
            logger.warn(
                LogCategory.SYSTEM,
                "Launch Services refused a default handler",
                mapOf(kind to target, "bundleId" to bundleId, "status" to status),
            )
        }
    }

    /**
     * Reads a `+1` CFString result and releases it.
     *
     * The `LSCopy*` naming is the Core Foundation ownership convention: the
     * caller owns the returned string. Wrapping the pointer in a [CFStringRef]
     * does not take ownership, so the release has to be explicit.
     */
    private inline fun copiedString(copy: () -> Pointer?): String? {
        val pointer = copy() ?: return null
        val ref = CFStringRef(pointer)
        return try {
            ref.stringValue()
        } finally {
            ref.release()
        }
    }

    /** Runs [block] with [value] as a CFString, releasing it afterwards. */
    private inline fun <T> withCFString(
        value: String,
        block: (CFStringRef) -> T,
    ): T {
        val ref = CFStringRef.createCFString(value)
        return try {
            block(ref)
        } finally {
            ref.release()
        }
    }
}

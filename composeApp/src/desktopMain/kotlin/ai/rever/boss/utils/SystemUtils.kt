package ai.rever.boss.utils

actual object SystemUtils {
    private val osName: String = System.getProperty("os.name").lowercase()

    actual fun getUserHome(): String = System.getProperty("user.home")

    actual fun getCurrentDirectory(): String = System.getProperty("user.dir") ?: getUserHome()

    // getDefaultProjectPath() was here and had no callers. It answered with the process
    // working directory - "/" for a packaged .app - and the one thing that would want it,
    // "where does BOSS work with no project selected", is DefaultWorkingDirectory.ensureDefaultDirectory().

    actual val isMacOS: Boolean = osName.contains("mac")

    actual val isWindows: Boolean = osName.contains("windows")

    actual val isLinux: Boolean = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")
}

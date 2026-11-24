package ai.rever.boss.crash.pluginprobe

import ai.rever.boss.plugin.loader.PluginClassLoader
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds a real plugin jar out of the fixture classes and loads it through a real
 * [PluginClassLoader].
 *
 * Shared by the attribution tests because the thing being pinned - that a lambda
 * compiled inside plugin code reports the plugin's classloader - cannot be faked
 * without also faking the answer. Kotlin lambdas are `invokedynamic` hidden
 * classes, and only a genuinely plugin-defined host class gives them a
 * plugin-defined loader.
 */
object PluginProbeJar {
    const val PLUGIN_ID = "test.plugin.probe"
    const val PROBE_CLASS = "ai.rever.boss.crash.pluginprobe.PluginProbe"
    const val EXCEPTION_CLASS = "ai.rever.boss.crash.pluginprobe.ProbeException"

    /** File facade holding the top-level fixture functions. */
    const val FACADE_CLASS = "ai.rever.boss.crash.pluginprobe.PluginProbeKt"

    /** The jar plus a loader over it; close the loader and delete the jar when done. */
    fun open(parent: ClassLoader): Handle {
        val jar = File.createTempFile("plugin-probe", ".jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for (className in listOf(PROBE_CLASS, EXCEPTION_CLASS, FACADE_CLASS)) {
                val resource = className.replace('.', '/') + ".class"
                val bytes =
                    checkNotNull(parent.getResourceAsStream(resource)) {
                        "fixture class $resource not on test classpath"
                    }.use { it.readBytes() }
                out.putNextEntry(JarEntry(resource))
                out.write(bytes)
                out.closeEntry()
            }
        }
        val loader =
            PluginClassLoader(
                pluginId = PLUGIN_ID,
                urls = arrayOf(jar.toURI().toURL()),
                parent = parent,
            )
        return Handle(jar, loader)
    }

    class Handle(
        private val jar: File,
        val loader: PluginClassLoader,
    ) {
        /** A plugin-defined lambda from [FACADE_CLASS], by function name. */
        @Suppress("UNCHECKED_CAST")
        fun action(
            function: String,
            vararg args: Any?,
        ): () -> Unit {
            val facade = loader.loadClass(FACADE_CLASS)
            check(facade.classLoader == loader) { "the facade must be plugin-defined, or this proves nothing" }
            // single, not first: getMethods() order is unspecified, so an added
            // overload would otherwise be picked arbitrarily and silently.
            val method = facade.methods.single { it.name == function }
            return method.invoke(null, *args) as () -> Unit
        }

        fun close() {
            loader.close()
            jar.delete()
        }
    }
}

package ai.rever.boss.plugin.repository.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the store's detail response has to keep decoding through [PluginStoreClient.json].
 *
 * `plugin_versions.dependencies` is free-form JSONB, so the client meets more than one shape for one
 * concept: the store's contract says `{pluginId, versionRange}`, while rows written from a plugin
 * manifest say `{pluginId, version, optional}`. `DependencyInfo.versionRange` used to be required,
 * and a missing required field is a hard error even with `ignoreUnknownKeys` - which forgives extra
 * keys and nothing else. The throw happened while decoding the whole document, so a single
 * legacy-shaped dependency failed `getPlugin` for the entire plugin and the first-run wizard
 * reported "Tool not found in repository" for a row that was present and valid.
 *
 * Decoded through the client's own `json` rather than a lookalike, because the property under test is
 * that the REAL client survives a REAL payload.
 */
class PluginStoreResponseDecodingTest {
    @Test
    fun `a dependency in the manifest shape does not fail the whole response`() {
        // Trimmed from the live response for ai.rever.boss.plugin.dynamic.flowtab 1.0.14, which is
        // the row that could not be installed.
        val body =
            """
            {
              "id": "0f6a1c62-0000-4000-8000-000000000001",
              "pluginId": "ai.rever.boss.plugin.dynamic.flowtab",
              "displayName": "Flow",
              "description": "Visual flow builder",
              "authorName": "RISA Labs",
              "type": "tab",
              "apiVersion": "1.0",
              "verified": true,
              "versions": [
                {
                  "id": "0f6a1c62-0000-4000-8000-000000000002",
                  "version": "1.0.14",
                  "dependencies": [
                    {
                      "version": "1.0.14",
                      "optional": true,
                      "pluginId": "ai.rever.boss.plugin.dynamic.aigateway"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val decoded = PluginStoreClient.json.decodeFromString<PluginDetailResponse>(body)

        val dependency =
            decoded.versions
                .single()
                .dependencies
                .single()
        // pluginId is the only part any consumer reads: RemotePluginRepository maps dependencies to
        // ids and drops the rest.
        assertEquals("ai.rever.boss.plugin.dynamic.aigateway", dependency.pluginId)
        assertEquals("", dependency.versionRange, "an absent versionRange must default, not throw")
    }

    @Test
    fun `a dependency in the store's documented shape still decodes`() {
        val body =
            """
            {"pluginId": "ai.rever.boss.plugin.dynamic.aigateway", "versionRange": ">=1.0.0"}
            """.trimIndent()

        val decoded = PluginStoreClient.json.decodeFromString<DependencyInfo>(body)

        assertEquals(">=1.0.0", decoded.versionRange)
    }

    @Test
    fun `publishing a dependency still sends versionRange as a string`() {
        // The same type is the request body of PublishVersionRequest and the server declares
        // versionRange as z.string(), so the default must encode as "" - a nullable field would
        // publish null and be rejected.
        val encoded =
            PluginStoreClient.json.encodeToString(
                DependencyInfo(pluginId = "ai.rever.boss.plugin.dynamic.aigateway"),
            )

        assertTrue(
            "\"versionRange\":\"\"" in encoded,
            "expected versionRange to be encoded as a string, got $encoded",
        )
    }
}

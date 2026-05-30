package com.mineinabyss.keepup

import com.mineinabyss.keepup.api.Keepup
import com.mineinabyss.keepup.config_sync.Inventory
import com.mineinabyss.keepup.config_sync.templating.Templater
import org.junit.Test
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplatingLineEndingTest {

    /**
     * Regression test: inventory files with CRLF line endings must not inject \r into template output.
     *
     * Root cause: kaml's YamlScalar.content includes the \r from CRLF endings for unquoted scalars,
     * and parseNode returns it verbatim. When that value is substituted into a .peb template the
     * resulting file is corrupted (e.g. "enabled: true\r\n    cache:" renders as "enabled: true cache").
     */
    @Test
    fun `should not inject carriage returns when inventory uses CRLF line endings`() {
        val keepup = Keepup()
        val tempDir = createTempDirectory("keepup-crlf-test")

        // Inventory with Windows-style CRLF endings — unquoted scalar values are the problematic case
        val inventoryContent = buildString {
            append("crlf-test-host:\r\n")
            append("  variables:\r\n")
            append("    TRANSFER_ENABLED: true\r\n")
            append("  copyPaths:\r\n")
            append("    - from: crlf-config\r\n")
        }

        // Template that reproduces the reported corruption pattern
        val configsRoot = tempDir / "configs"
        val sourceDir = configsRoot / "crlf-config"
        sourceDir.createDirectories()
        (sourceDir / "config.yml.peb").writeText(
            "transfer:\n  enabled: {{ TRANSFER_ENABLED }}\ncache:\n"
        )

        val dest = tempDir / "destRoot"
        val templater = Templater()

        keepup.configSync(
            inventory = Inventory.from(
                templater = templater,
                inputStream = inventoryContent.byteInputStream(),
                environment = emptyMap(),
                enableDockerSecrets = false,
            ),
        ).sync(
            host = "crlf-test-host",
            configsRoot = configsRoot,
            templateCacheDir = tempDir / "cacheDir",
            destRoot = dest,
        )

        val outputFile = dest / "config.yml"
        assertTrue(outputFile.exists(), "Output file should be written")
        val content = outputFile.readText()

        assertFalse(content.contains('\r'), "Output must not contain \\r from CRLF inventory")
        assertEquals("transfer:\n  enabled: true\ncache:\n", content)
    }
}

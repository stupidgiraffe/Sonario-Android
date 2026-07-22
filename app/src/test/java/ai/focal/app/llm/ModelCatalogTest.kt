package ai.focal.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `catalog records exact public artifact sizes`() {
        assertEquals(
            listOf(2_497_280_736L, 4_237_063_776L, 1_563_668_704L),
            BUNDLED_MODELS.map(ModelInfo::sizeBytes),
        )
        assertEquals(3, BUNDLED_MODELS.map(ModelInfo::fileName).toSet().size)
        assertTrue(BUNDLED_MODELS.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(BUNDLED_MODELS.all { it.downloadUrl.startsWith("https://huggingface.co/") })
    }

    @Test
    fun `cleanup is allowlisted and preserves session referenced legacy model`() {
        val dir = temporaryFolder.newFolder("models")
        val removable = LEGACY_MODELS[0].fileName
        val protected = LEGACY_MODELS[1].fileName
        java.io.File(dir, removable).writeText("old")
        java.io.File(dir, "$removable.part").writeText("partial")
        java.io.File(dir, protected).writeText("needed")
        java.io.File(dir, "$protected.part").writeText("obsolete partial")
        java.io.File(dir, "user-sideloaded.gguf").writeText("keep")

        val result = LegacyModelCleanup.clean(dir, setOf(protected))

        assertFalse(java.io.File(dir, removable).exists())
        assertFalse(java.io.File(dir, "$removable.part").exists())
        assertTrue(java.io.File(dir, protected).exists())
        assertFalse(java.io.File(dir, "$protected.part").exists())
        assertTrue(java.io.File(dir, "user-sideloaded.gguf").exists())
        assertEquals(
            setOf(removable, "$removable.part", "$protected.part"),
            result.deleted,
        )
        assertTrue(result.failed.isEmpty())
    }
}

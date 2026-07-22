package ai.focal.app.data

import ai.focal.app.llm.LlmProvider
import ai.focal.app.llm.RateLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMigrationTest {
    @Test
    fun `schema one Groq session migrates explicitly to cloud Groq and Qwen`() {
        val migrated = SessionMigration.migrate(
            schema = 1,
            engineChoice = "GROQ",
            cloudProviderId = null,
            cloudModel = null,
            legacyGroqModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        )

        assertEquals(EngineChoice.CLOUD, migrated.engineChoice)
        assertEquals(LlmProvider.GROQ.id, migrated.cloudProviderId)
        assertEquals(RateLimiter.GROQ_QWEN_MODEL, migrated.cloudModel)
        assertNull(migrated.issue)
    }

    @Test
    fun `current custom provider and model remain unchanged`() {
        val migrated = SessionMigration.migrate(
            schema = 3,
            engineChoice = "CLOUD",
            cloudProviderId = LlmProvider.CUSTOM.id,
            cloudModel = "private-model-v2",
            legacyGroqModel = "ignored-legacy-model",
        )

        assertEquals(LlmProvider.CUSTOM.id, migrated.cloudProviderId)
        assertEquals("private-model-v2", migrated.cloudModel)
        assertNull(migrated.issue)
    }

    @Test
    fun `unknown provider is preserved and marked unsupported`() {
        val migrated = SessionMigration.migrate(
            schema = 3,
            engineChoice = "CLOUD",
            cloudProviderId = "future-provider",
            cloudModel = "future-model",
            legacyGroqModel = null,
        )

        assertEquals("future-provider", migrated.cloudProviderId)
        assertEquals("future-model", migrated.cloudModel)
        assertTrue(migrated.issue!!.contains("unsupported cloud provider"))
    }

    @Test
    fun `unknown engine fails safe and is reported`() {
        val migrated = SessionMigration.migrate(
            schema = 3,
            engineChoice = "AUTOMATIC_FALLBACK",
            cloudProviderId = LlmProvider.OPENAI.id,
            cloudModel = "gpt-4o-mini",
            legacyGroqModel = null,
        )

        assertEquals(EngineChoice.ON_DEVICE, migrated.engineChoice)
        assertTrue(migrated.issue!!.contains("unsupported engine"))
    }
}

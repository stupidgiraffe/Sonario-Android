package ai.sonario.app.data

import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.RateLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun `retired Groq default migrates without changing other provider models`() {
        assertEquals(
            RateLimiter.GROQ_QWEN_MODEL,
            Settings.migrateModelId(
                LlmProvider.GROQ,
                "meta-llama/llama-4-scout-17b-16e-instruct",
            ),
        )
        assertEquals(
            "custom-model",
            Settings.migrateModelId(LlmProvider.GROQ, "custom-model"),
        )
        assertEquals(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            Settings.migrateModelId(
                LlmProvider.CUSTOM,
                "meta-llama/llama-4-scout-17b-16e-instruct",
            ),
        )
    }
}

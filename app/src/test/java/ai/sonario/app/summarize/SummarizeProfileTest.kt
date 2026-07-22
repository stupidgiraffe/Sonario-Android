package ai.sonario.app.summarize

import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.ProviderConfig
import ai.sonario.app.llm.RateLimiter
import org.junit.Assert.assertEquals
import org.junit.Test

class SummarizeProfileTest {
    @Test
    fun `only verified Groq Qwen model receives free tier request budget`() {
        assertEquals(
            SummarizeEngine.Profile.GROQ_QWEN_FREE,
            SummarizeEngine.profileFor(
                ProviderConfig(LlmProvider.GROQ, RateLimiter.GROQ_QWEN_MODEL)
            ),
        )
        assertEquals(
            SummarizeEngine.Profile.CLOUD_LARGE_CONTEXT,
            SummarizeEngine.profileFor(
                ProviderConfig(LlmProvider.GROQ, "llama-3.3-70b-versatile")
            ),
        )
        assertEquals(
            SummarizeEngine.Profile.CLOUD_LARGE_CONTEXT,
            SummarizeEngine.profileFor(
                ProviderConfig(LlmProvider.ANTHROPIC, "claude-sonnet")
            ),
        )
    }
}

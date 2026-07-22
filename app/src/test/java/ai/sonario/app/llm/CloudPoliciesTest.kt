package ai.sonario.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPoliciesTest {
    @Test
    fun `local quota capability is exact to provider and model`() {
        assertTrue(
            ProviderCapabilities.usesGroqQwenPolicy(
                ProviderConfig(LlmProvider.GROQ, RateLimiter.GROQ_QWEN_MODEL)
            )
        )
        assertFalse(
            ProviderCapabilities.usesGroqQwenPolicy(
                ProviderConfig(LlmProvider.GROQ, "custom-model")
            )
        )
        assertFalse(
            ProviderCapabilities.usesGroqQwenPolicy(
                ProviderConfig(LlmProvider.CUSTOM, RateLimiter.GROQ_QWEN_MODEL)
            )
        )
    }

    @Test
    fun `provider duration formats convert to milliseconds`() {
        assertEquals(250L, RetryDelayParser.durationMillis("250ms"))
        assertEquals(7_660L, RetryDelayParser.durationMillis("7.66s"))
        assertEquals(62_500L, RetryDelayParser.durationMillis("1m2.5s"))
        assertEquals(3_600_000L, RetryDelayParser.durationMillis("1h"))
        assertEquals(1_500L, RetryDelayParser.retryAfterMillis("1.5"))
        assertEquals(
            60_000L,
            RetryDelayParser.retryAfterMillis(
                "Wed, 21 Oct 2015 07:28:00 GMT",
                nowMillis = 1_445_412_420_000L,
            ),
        )
    }

    @Test
    fun `Groq headers stay isolated in Groq parser`() {
        val values = mapOf(
            "x-ratelimit-limit-tokens" to "8000",
            "x-ratelimit-remaining-tokens" to "1234",
            "x-ratelimit-reset-tokens" to "8.5s",
        )

        val parsed = GroqRateLimitHeaders.parse(values::get)

        assertEquals(8_000L, parsed.limitTokens)
        assertEquals(1_234L, parsed.remainingTokens)
        assertEquals(8_500L, parsed.resetAfterMs)
    }

    @Test
    fun `daily quota classification does not swallow minute errors`() {
        assertTrue(GroqPolicy.isDailyLimit("Limit reached: tokens per day (TPD)"))
        assertTrue(GroqPolicy.isDailyLimit("daily request quota exhausted"))
        assertFalse(GroqPolicy.isDailyLimit("tokens per minute exceeded; retry in 7s"))
    }

    @Test
    fun `server retries use bounded exponential backoff`() {
        assertEquals(2_000L, ServerRetryPolicy.delayMillis(0))
        assertEquals(4_000L, ServerRetryPolicy.delayMillis(1))
        assertEquals(8_000L, ServerRetryPolicy.delayMillis(2))
        assertEquals(null, ServerRetryPolicy.delayMillis(3))
    }
}

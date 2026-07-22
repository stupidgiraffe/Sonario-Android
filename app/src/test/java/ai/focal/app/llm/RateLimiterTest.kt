package ai.focal.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimiterTest {
    private val config = RateLimiter.Config(
        storageKey = "test",
        providerName = "Test",
        tokensPerMinute = 100,
        tokensPerDay = 1_000,
        requestsPerMinute = 2,
        maxRequestTokens = 90,
    )

    @Test
    fun `reservations enforce token window and actual usage is reconciled`() {
        var now = 0L
        val window = RateLimitWindow(config) { now }
        val first = window.reserve(60)

        assertEquals(60_000L, window.waitMillisFor(50))

        window.reconcile(first.id, 40)
        assertEquals(0L, window.waitMillisFor(50))

        now = 60_000L
        assertEquals(0L, window.waitMillisFor(90))
    }

    @Test
    fun `request window preserves rpm headroom`() {
        var now = 1_000L
        val window = RateLimitWindow(config) { now }
        window.reserve(1)
        window.reserve(1)

        assertEquals(60_000L, window.waitMillisFor(1))

        now = 61_000L
        assertEquals(0L, window.waitMillisFor(1))
    }

    @Test
    fun `transport retries consume request slots without consuming more tokens`() {
        val window = RateLimitWindow(config) { 0L }
        window.reserve(50)
        window.reserveRequest()

        assertEquals(60_000L, window.waitMillisFor(1))

        window.reconcile(1L, 0L)
        assertEquals(60_000L, window.waitMillisFor(1))
    }

    @Test
    fun `server token window and explicit backoff are both honored`() {
        var now = 10_000L
        val window = RateLimitWindow(config) { now }
        window.syncServerTokenWindow(limit = 100, remaining = 10, resetAfterMs = 5_000)
        window.markServerBackoff(7_000)

        assertEquals(7_000L, window.waitMillisFor(20))

        now = 17_000L
        assertEquals(0L, window.waitMillisFor(20))
    }
}

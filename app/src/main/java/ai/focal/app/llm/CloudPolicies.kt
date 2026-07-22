package ai.focal.app.llm

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object ProviderCapabilities {
    fun usesGroqQwenPolicy(config: ProviderConfig): Boolean =
        config.provider == LlmProvider.GROQ &&
            config.model.trim() == RateLimiter.GROQ_QWEN_MODEL

    fun localRateLimit(config: ProviderConfig): RateLimiter.Config? =
        RateLimiter.GROQ_QWEN.takeIf { usesGroqQwenPolicy(config) }
}

internal data class GroqRateLimitHeaders(
    val limitTokens: Long?,
    val remainingTokens: Long?,
    val resetAfterMs: Long?,
) {
    companion object {
        fun parse(header: (String) -> String?): GroqRateLimitHeaders = GroqRateLimitHeaders(
            limitTokens = header("x-ratelimit-limit-tokens")?.toLongOrNull(),
            remainingTokens = header("x-ratelimit-remaining-tokens")?.toLongOrNull(),
            resetAfterMs = RetryDelayParser.durationMillis(
                header("x-ratelimit-reset-tokens")
            ),
        )
    }
}

internal object GroqPolicy {
    fun isDailyLimit(message: String?): Boolean {
        val lower = message.orEmpty().lowercase()
        return "tokens per day" in lower || "requests per day" in lower ||
            "daily token" in lower || "daily request" in lower ||
            Regex("\\btpd\\b").containsMatchIn(lower) ||
            Regex("\\brpd\\b").containsMatchIn(lower)
    }
}

internal object RetryDelayParser {
    private val unitRegex = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|d|h|m|s)")

    /** Parses provider durations such as `250ms`, `7.66s`, or `1m2.5s`. */
    fun durationMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        var total = 0.0
        var found = false
        unitRegex.findAll(value.lowercase()).forEach { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            found = true
            total += when (match.groupValues[2]) {
                "d" -> amount * 86_400_000.0
                "h" -> amount * 3_600_000.0
                "m" -> amount * 60_000.0
                "s" -> amount * 1_000.0
                "ms" -> amount
                else -> 0.0
            }
        }
        return if (found) total.toLong().coerceAtLeast(1L) else null
    }

    /** Retry-After without a unit is defined as seconds for these APIs. */
    fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        trimmed.toDoubleOrNull()?.let {
            return (it * 1_000.0).toLong().coerceAtLeast(1L)
        }
        return runCatching {
            val retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (retryAt - nowMillis).coerceAtLeast(1L)
        }.getOrNull()
    }
}

internal object ServerRetryPolicy {
    const val MAX_RETRIES = 3

    fun delayMillis(completedRetries: Int): Long? =
        if (completedRetries >= MAX_RETRIES) null
        else minOf(30_000L, (1L shl (completedRetries + 1)) * 1_000L)
}

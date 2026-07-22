package ai.sonario.app.llm

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/**
 * Provider-scoped request pacing. A limiter exists only when Focal has a
 * verified quota policy for the selected provider/model; other providers rely
 * on their server's HTTP 429 response and retry headers.
 */
class RateLimiter(
    context: Context,
    val config: Config,
) {
    data class Config(
        val storageKey: String,
        val providerName: String,
        val tokensPerMinute: Long,
        val tokensPerDay: Long,
        val requestsPerMinute: Int,
        val maxRequestTokens: Long,
    )

    class Reservation internal constructor(
        internal val windowId: Long,
        internal val estimatedTokens: Long,
    )

    data class DailyUsage(val used: Long, val limit: Long) {
        val remaining: Long get() = (limit - used).coerceAtLeast(0L)
    }

    data class Estimate(
        val inputTokens: Long,
        val totalTokens: Long,
        val dailyRemaining: Long,
        val dailyLimit: Long,
        val exceedsDaily: Boolean,
        val etaSeconds: Long,
    ) {
        val percentOfRemaining: Int = if (dailyRemaining > 0L) {
            ((totalTokens.toDouble() / dailyRemaining) * 100).toInt().coerceIn(0, 999)
        } else {
            100
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "rate_limiter_${config.storageKey}",
        Context.MODE_PRIVATE,
    )
    private val queue = Mutex()
    private val window = RateLimitWindow(config)
    private val dailyLock = Any()

    init {
        migrateLegacyGroqUsage()
    }

    fun dailyUsage(): DailyUsage = DailyUsage(dailyUsed(), config.tokensPerDay)

    fun resetDaily() {
        prefs.edit().putString(DAY_KEY, today()).putLong(USED_KEY, 0L).apply()
        window.clear()
    }

    fun estimate(sourceText: String): Estimate {
        val input = estimateTokens(sourceText)
        val total = (input * 2.4).toLong() + 5_000L
        val remaining = dailyUsage().remaining
        val windows = ceil(total.toDouble() / config.tokensPerMinute).toLong()
        return Estimate(
            inputTokens = input,
            totalTokens = total,
            dailyRemaining = remaining,
            dailyLimit = config.tokensPerDay,
            exceedsDaily = total > remaining,
            etaSeconds = (windows * 60L).coerceAtLeast(4L),
        )
    }

    /**
     * Wait in FIFO order, then reserve both request and estimated token capacity.
     * The caller must later invoke [record] or [cancel].
     */
    suspend fun awaitSlot(tokens: Long, onWaiting: (Long) -> Unit): Reservation =
        queue.withLock {
            require(tokens in 1..config.maxRequestTokens) {
                "This ${config.providerName} request is too large for the verified " +
                    "${config.tokensPerMinute}-token minute limit. Focal must split it first."
            }
            if (dailyUsed() + tokens > config.tokensPerDay) {
                throw RateLimitException(
                    "Focal's conservative daily ${config.providerName} budget has been reached. " +
                        "Completed checkpoints are saved; resume after the provider resets it."
                )
            }

            while (true) {
                val waitMs = window.waitMillisFor(tokens)
                if (waitMs <= 0L) break
                onWaiting(((waitMs + 999L) / 1_000L).coerceAtLeast(1L))
                delay(minOf(waitMs, 1_000L))
            }
            onWaiting(0L)
            val reservation = window.reserve(tokens)
            adjustDaily(tokens)
            Reservation(reservation.id, tokens)
        }

    /** Reconcile a successful response's actual token usage with its reservation. */
    fun record(reservation: Reservation, actualTokens: Long) {
        val actual = actualTokens.coerceAtLeast(0L)
        window.reconcile(reservation.windowId, actual)
        adjustDaily(actual - reservation.estimatedTokens)
    }

    /** Release token capacity for a rejected request while retaining its RPM slot. */
    fun cancel(reservation: Reservation) {
        window.reconcile(reservation.windowId, 0L)
        adjustDaily(-reservation.estimatedTokens)
    }

    /** Queue a transport retry against the RPM window without reserving tokens twice. */
    suspend fun awaitRetry(onWaiting: (Long) -> Unit) {
        queue.withLock {
            while (true) {
                val waitMs = window.waitMillisFor(0L)
                if (waitMs <= 0L) break
                onWaiting(((waitMs + 999L) / 1_000L).coerceAtLeast(1L))
                delay(minOf(waitMs, 1_000L))
            }
            onWaiting(0L)
            window.reserveRequest()
        }
    }

    fun syncServerTokenWindow(limit: Long?, remaining: Long?, resetAfterMs: Long?) {
        window.syncServerTokenWindow(limit, remaining, resetAfterMs)
    }

    fun markServerBackoff(waitMs: Long) {
        window.markServerBackoff(waitMs)
    }

    private fun today(): String = (System.currentTimeMillis() / DAY_MS).toString()

    private fun dailyUsed(): Long = synchronized(dailyLock) {
        if (prefs.getString(DAY_KEY, null) == today()) prefs.getLong(USED_KEY, 0L) else 0L
    }

    private fun adjustDaily(delta: Long) {
        synchronized(dailyLock) {
            val used = if (prefs.getString(DAY_KEY, null) == today()) {
                prefs.getLong(USED_KEY, 0L)
            } else {
                0L
            }
            prefs.edit()
                .putString(DAY_KEY, today())
                .putLong(USED_KEY, (used + delta).coerceAtLeast(0L))
                .commit()
        }
    }

    /** Carry the repaired build's old generic counter into the verified Groq policy once. */
    private fun migrateLegacyGroqUsage() {
        if (config.storageKey != GROQ_QWEN.storageKey || prefs.contains(MIGRATED_KEY)) return
        val legacy = appContext.getSharedPreferences("rate_limiter", Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(MIGRATED_KEY, true)
        val day = legacy.getString(DAY_KEY, null)
        if (day != null && day == today()) {
            editor.putString(DAY_KEY, day).putLong(USED_KEY, legacy.getLong(USED_KEY, 0L))
        }
        editor.apply()
    }

    companion object {
        const val GROQ_QWEN_MODEL = "qwen/qwen3.6-27b"
        const val PUBLISHED_GROQ_TPM = 8_000L
        const val PUBLISHED_GROQ_TPD = 200_000L
        const val PUBLISHED_GROQ_RPM = 30

        val GROQ_QWEN = Config(
            storageKey = "groq_qwen36",
            providerName = "Groq",
            tokensPerMinute = 7_600L,
            tokensPerDay = 195_000L,
            requestsPerMinute = 28,
            maxRequestTokens = 7_400L,
        )

        private const val DAY_MS = 86_400_000L
        private const val DAY_KEY = "day"
        private const val USED_KEY = "used"
        private const val MIGRATED_KEY = "legacy_generic_migrated"

        /** Rough estimate: about four UTF-16 characters per model token. */
        fun estimateTokens(text: String): Long = (text.length / 4L) + 16L
    }
}

/** Returns a stable limiter only for provider/model combinations with verified limits. */
class ProviderRateLimiters(context: Context) {
    private val appContext = context.applicationContext
    private val limiters = mutableMapOf<String, RateLimiter>()

    @Synchronized
    fun forConfig(config: ProviderConfig): RateLimiter? {
        val policy = ProviderCapabilities.localRateLimit(config) ?: return null
        return limiters.getOrPut(policy.storageKey) { RateLimiter(appContext, policy) }
    }
}

/** Pure rolling-window state, split out so quota behavior is deterministic in unit tests. */
internal class RateLimitWindow(
    private val config: RateLimiter.Config,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Reservation(val id: Long)
    private data class TokenSpend(val id: Long, val timestamp: Long, var tokens: Long)

    private val tokenWindow = ArrayDeque<TokenSpend>()
    private val requestWindow = ArrayDeque<Long>()
    private var nextId = 1L
    private var serverRemainingTokens: Long? = null
    private var serverTokenResetAtMs = 0L
    private var serverBlockedUntilMs = 0L

    @Synchronized
    fun clear() {
        tokenWindow.clear()
        requestWindow.clear()
        serverRemainingTokens = null
        serverTokenResetAtMs = 0L
        serverBlockedUntilMs = 0L
    }

    @Synchronized
    fun waitMillisFor(tokens: Long): Long {
        val now = clock()
        trim(now)
        var waitMs = 0L

        val localTokens = tokenWindow.sumOf(TokenSpend::tokens)
        if (localTokens + tokens > config.tokensPerMinute && tokenWindow.isNotEmpty()) {
            var needed = localTokens + tokens - config.tokensPerMinute
            for (entry in tokenWindow) {
                needed -= entry.tokens
                if (needed <= 0L) {
                    waitMs = maxOf(waitMs, entry.timestamp + WINDOW_MS - now)
                    break
                }
            }
        }
        if (requestWindow.size >= config.requestsPerMinute && requestWindow.isNotEmpty()) {
            waitMs = maxOf(waitMs, requestWindow.first() + WINDOW_MS - now)
        }
        if (serverBlockedUntilMs > now) {
            waitMs = maxOf(waitMs, serverBlockedUntilMs - now)
        }
        if (serverTokenResetAtMs <= now) {
            serverRemainingTokens = null
            serverTokenResetAtMs = 0L
        } else if (tokens > (serverRemainingTokens ?: Long.MAX_VALUE)) {
            waitMs = maxOf(waitMs, serverTokenResetAtMs - now)
        }
        return waitMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun reserve(tokens: Long): Reservation {
        val now = clock()
        trim(now)
        val id = nextId++
        tokenWindow.addLast(TokenSpend(id, now, tokens))
        requestWindow.addLast(now)
        serverRemainingTokens = serverRemainingTokens?.let { (it - tokens).coerceAtLeast(0L) }
        return Reservation(id)
    }

    @Synchronized
    fun reserveRequest() {
        val now = clock()
        trim(now)
        requestWindow.addLast(now)
    }

    @Synchronized
    fun reconcile(id: Long, actualTokens: Long) {
        tokenWindow.firstOrNull { it.id == id }?.tokens = actualTokens.coerceAtLeast(0L)
    }

    @Synchronized
    fun syncServerTokenWindow(limit: Long?, remaining: Long?, resetAfterMs: Long?) {
        if (remaining == null || resetAfterMs == null || resetAfterMs <= 0L) return
        if (limit != null && limit <= 0L) return
        serverRemainingTokens = remaining.coerceAtLeast(0L)
        serverTokenResetAtMs = clock() + resetAfterMs
    }

    @Synchronized
    fun markServerBackoff(waitMs: Long) {
        if (waitMs > 0L) serverBlockedUntilMs = maxOf(serverBlockedUntilMs, clock() + waitMs)
    }

    private fun trim(now: Long) {
        while (tokenWindow.isNotEmpty() && now - tokenWindow.first().timestamp >= WINDOW_MS) {
            tokenWindow.removeFirst()
        }
        while (requestWindow.isNotEmpty() && now - requestWindow.first() >= WINDOW_MS) {
            requestWindow.removeFirst()
        }
    }

    companion object {
        private const val WINDOW_MS = 60_000L
    }
}

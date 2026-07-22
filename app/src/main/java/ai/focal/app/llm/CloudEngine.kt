package ai.focal.app.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Cloud inference through any OpenAI-compatible chat-completions API, with
 * native Anthropic Messages API support.
 *
 * Replaces the old Groq-only [GroqEngine]. Supports:
 *   - Groq, OpenAI, OpenRouter, Together, Fireworks, etc. (OpenAI wire format)
 *   - Anthropic (Claude) via its native Messages API
 *   - Ollama and other local servers (OpenAI-compatible)
 *   - Any custom OpenAI-compatible proxy via [ProviderConfig.resolvedBaseUrl]
 *
 * Requests are buffered before tokens are emitted so Android brief Wi-Fi
 * suspension / network change / DNS loss can be retried without duplicating a
 * partial answer in the UI.
 */
class CloudEngine(
    context: Context,
    private val configProvider: () -> ProviderConfig,
    private val apiKeyProvider: () -> String?,
    private val rateLimiterProvider: (ProviderConfig) -> RateLimiter? = { null },
    private val onRateWait: (Long) -> Unit = {},
    private val onNetworkStatus: (String?) -> Unit = {},
) : InferenceEngine {

    private val appContext = context.applicationContext
    private val connectivity = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    override suspend fun ensureReady(model: ModelInfo) {
        val config = configProvider()
        config.validationError()?.let { throw IllegalStateException(it) }
        if (config.provider.needsKey) {
            val key = apiKeyProvider()
            require(!key.isNullOrBlank()) {
                "No ${config.provider.displayName} API key is set. Open Settings → Providers to add one."
            }
        }
    }

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> =
        flow {
            val config = configProvider()
            config.validationError()?.let { throw IllegalStateException(it) }
            val key = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            if (config.provider.needsKey && key.isNullOrBlank()) {
                throw IllegalStateException(
                    "No ${config.provider.displayName} API key is set."
                )
            }
            val estimatedInput = RateLimiter.estimateTokens(system) +
                RateLimiter.estimateTokens(user)
            val estimatedTotal = estimatedInput + maxTokens
            val rateLimiter = rateLimiterProvider(config)
            val reservation = rateLimiter?.awaitSlot(estimatedTotal) { seconds ->
                onRateWait(seconds)
            }
            onRateWait(0)

            val parsed = try {
                when (config.provider) {
                    LlmProvider.ANTHROPIC -> requestAnthropic(
                        config, key, system, user, maxTokens, estimatedTotal, rateLimiter,
                    )
                    else -> requestOpenAICompatible(
                        config, key, system, user, maxTokens, estimatedTotal, rateLimiter,
                    )
                }
            } catch (error: Throwable) {
                if (reservation != null) rateLimiter?.cancel(reservation)
                throw error
            }

            if (reservation != null) {
                rateLimiter?.record(reservation, parsed.usageTokens ?: estimatedTotal)
            }
            onNetworkStatus(null)

            // Preserve the InferenceEngine streaming contract while only exposing
            // a response after it has completed successfully.
            parsed.text.chunked(96).forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

    // ── OpenAI-compatible path (Groq, OpenAI, Ollama, custom, …) ──────────────

    private suspend fun requestOpenAICompatible(
        config: ProviderConfig,
        key: String?,
        system: String,
        user: String,
        maxTokens: Int,
        estimatedTotal: Long,
        rateLimiter: RateLimiter?,
    ): ParsedCompletion {
        val endpoint = "${config.resolvedBaseUrl.trimEnd('/')}/chat/completions"
        val payload = CloudRequestPayloads.openAiCompatible(config, system, user, maxTokens)
        return performRequest(
            endpoint = endpoint,
            payload = payload,
            estimatedTotal = estimatedTotal,
            authHeader = if (key.isNullOrBlank()) "" else "Bearer $key",
            config = config,
            rateLimiter = rateLimiter,
        )
    }

    // ── Anthropic (Claude) path ────────────────────────────────────────────────

    private suspend fun requestAnthropic(
        config: ProviderConfig,
        key: String?,
        system: String,
        user: String,
        maxTokens: Int,
        estimatedTotal: Long,
        rateLimiter: RateLimiter?,
    ): ParsedCompletion {
        val endpoint = "${config.resolvedBaseUrl.trimEnd('/')}/messages"
        val payload = CloudRequestPayloads.anthropic(config, system, user, maxTokens)
        return performRequest(
            endpoint = endpoint,
            payload = payload,
            estimatedTotal = estimatedTotal,
            authHeader = if (key.isNullOrBlank()) "" else "$key",
            anthropic = true,
            config = config,
            rateLimiter = rateLimiter,
        )
    }

    // ── shared request + retry loop ────────────────────────────────────────────

    private suspend fun performRequest(
        endpoint: String,
        payload: String,
        estimatedTotal: Long,
        authHeader: String,
        anthropic: Boolean = false,
        config: ProviderConfig,
        rateLimiter: RateLimiter?,
    ): ParsedCompletion {
        val media = "application/json; charset=utf-8".toMediaTypeOrNull()
        val deadline = System.currentTimeMillis() + NETWORK_RETRY_WINDOW_MS
        var networkAttempt = 0
        var rateAttempt = 0
        var serverAttempt = 0

        requestLoop@ while (true) {
            val builder = Request.Builder()
                .url(endpoint)
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(media))
            if (anthropic) {
                if (authHeader.isNotBlank()) builder.header("x-api-key", authHeader)
                builder.header("anthropic-version", ANTHROPIC_VERSION)
                builder.header("anthropic-dangerous-direct-browser-access", "true")
            } else {
                if (authHeader.isNotBlank()) builder.header("Authorization", authHeader)
            }
            val request = builder.build()

            val call = http.newCall(request)
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            val response = try {
                call.execute()
            } catch (error: IOException) {
                cancellationHandle?.dispose()
                currentCoroutineContext().ensureActive()
                if (!isTransientNetworkError(error) || System.currentTimeMillis() >= deadline) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, deadline, error, config.provider.displayName)
                rateLimiter?.awaitRetry(onRateWait)
                continue@requestLoop
            }

            val code = response.code
            val retryAfterHeader = response.header("retry-after")
            val resetHeader = response.header("x-ratelimit-reset-tokens")
                ?: response.header("x-ratelimit-reset-requests")
            val usedHeader = response.header("x-ratelimit-used-tokens")
            if (config.provider == LlmProvider.GROQ) {
                val headers = GroqRateLimitHeaders.parse(response::header)
                rateLimiter?.syncServerTokenWindow(
                    headers.limitTokens,
                    headers.remainingTokens,
                    headers.resetAfterMs,
                )
            }

            val body = try {
                response.body?.string().orEmpty()
            } catch (error: IOException) {
                response.close()
                currentCoroutineContext().ensureActive()
                if (!isTransientNetworkError(error) || System.currentTimeMillis() >= deadline) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, deadline, error, config.provider.displayName)
                rateLimiter?.awaitRetry(onRateWait)
                continue@requestLoop
            } finally {
                response.close()
                cancellationHandle?.dispose()
            }

            if (code == 429) {
                val providerMessage = CloudErrorMapper.apiMessage(body)
                if (config.provider == LlmProvider.GROQ &&
                    GroqPolicy.isDailyLimit(providerMessage)
                ) {
                    throw RateLimitException(
                        "Groq's organization-wide daily limit has been reached. " +
                            "Focal saved completed checkpoints; resume after Groq resets it."
                    )
                }
                rateAttempt++
                if (rateAttempt > MAX_RATE_RETRIES) {
                    throw RateLimitException(
                        CloudErrorMapper.friendlyHttpError(code, body, config.provider.displayName)
                    )
                }
                val waitMs = retryWaitMillis(retryAfterHeader, resetHeader, providerMessage)
                    .coerceIn(1_000L, MAX_RATE_WAIT_MS)
                rateLimiter?.markServerBackoff(waitMs)
                var left = (waitMs + 999L) / 1_000L
                while (left > 0) {
                    onRateWait(left)
                    delay(1000)
                    left--
                }
                onRateWait(0)
                rateLimiter?.awaitRetry(onRateWait)
                continue@requestLoop
            }

            val serverWaitMs = if (code in 500..599) {
                ServerRetryPolicy.delayMillis(serverAttempt)
            } else {
                null
            }
            if (serverWaitMs != null) {
                serverAttempt++
                onNetworkStatus(
                    "${config.provider.displayName} had a temporary server error. " +
                        "Retrying in ${serverWaitMs / 1_000L}s (attempt ${serverAttempt + 1})."
                )
                delay(serverWaitMs)
                rateLimiter?.awaitRetry(onRateWait)
                continue@requestLoop
            }

            if (code !in 200..299) {
                val message =
                    CloudErrorMapper.friendlyHttpError(code, body, config.provider.displayName)
                when (code) {
                    401, 403 -> throw AuthenticationException(message)
                    else -> throw RuntimeException(message)
                }
            }

            val parsed = if (anthropic) {
                parseAnthropic(body, config.provider.displayName)
            } else {
                parseOpenAI(body, config.provider.displayName)
            }
            if (parsed.text.isBlank()) {
                throw RuntimeException(
                    "${config.provider.displayName} returned an empty answer. " +
                        "Check that the selected model is available, then try again."
                )
            }

            val usage = parsed.usageTokens ?: usedHeader?.toLongOrNull() ?: estimatedTotal
            return parsed.copy(usageTokens = usage)
        }
    }

    // ── OpenAI-compatible SSE / JSON parsing ───────────────────────────────────

    private fun parseOpenAI(raw: String, providerName: String): ParsedCompletion {
        if (raw.isBlank()) return ParsedCompletion("")
        val text = StringBuilder()
        var usage: Long? = null
        var sawSse = false

        raw.lineSequence().forEach { line ->
            if (!line.startsWith("data:")) return@forEach
            sawSse = true
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach
            val obj = try { JSONObject(data) } catch (_: Exception) { return@forEach }
            CloudErrorMapper.apiError(obj, providerName)?.let { throw RuntimeException(it) }
            val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("delta")?.optString("content", "").orEmpty()
            if (content.isNotEmpty()) text.append(content)
            usage = usageFromOpenAI(obj) ?: usage
        }
        if (sawSse) return ParsedCompletion(text.toString(), usage)

        val obj = try { JSONObject(raw) } catch (_: Exception) {
            throw RuntimeException(
                "Returned an unreadable response instead of JSON. Please try again."
            )
        }
        CloudErrorMapper.apiError(obj, providerName)?.let { throw RuntimeException(it) }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content", "")
            ?.ifBlank { choice?.optJSONObject("delta")?.optString("content", "").orEmpty() }
            .orEmpty()
        return ParsedCompletion(content, usageFromOpenAI(obj))
    }

    private fun usageFromOpenAI(obj: JSONObject): Long? {
        val direct = obj.optJSONObject("usage")?.optLong("total_tokens", -1L) ?: -1L
        if (direct >= 0) return direct
        val groq = obj.optJSONObject("x_groq")
            ?.optJSONObject("usage")
            ?.optLong("total_tokens", -1L) ?: -1L
        return groq.takeIf { it >= 0 }
    }

    // ── Anthropic SSE / JSON parsing ───────────────────────────────────────────

    private fun parseAnthropic(raw: String, providerName: String): ParsedCompletion {
        if (raw.isBlank()) return ParsedCompletion("")
        val text = StringBuilder()
        var usage: Long? = null
        var sawSse = false

        raw.lineSequence().forEach { line ->
            // Anthropic SSE: "event: content_block_delta" then "data: {...}"
            if (line.startsWith("event:")) return@forEach
            if (!line.startsWith("data:")) return@forEach
            sawSse = true
            val data = line.removePrefix("data:").trim()
            if (data.isBlank()) return@forEach
            val obj = try { JSONObject(data) } catch (_: Exception) { return@forEach }
            CloudErrorMapper.apiError(obj, providerName)?.let { throw RuntimeException(it) }
            val type = obj.optString("type", "")
            if (type == "content_block_delta") {
                val delta = obj.optJSONObject("delta")
                if (delta?.optString("type") == "text_delta") {
                    text.append(delta.optString("text", ""))
                }
            }
            if (type == "message_delta" || type == "message_start") {
                val usageObj = obj.optJSONObject("usage")
                    ?: obj.optJSONObject("message")?.optJSONObject("usage")
                if (usageObj != null) {
                    val input = usageObj.optLong("input_tokens", 0)
                    val output = usageObj.optLong("output_tokens", 0)
                    usage = input + output
                }
            }
        }
        if (sawSse) return ParsedCompletion(text.toString(), usage)

        val obj = try { JSONObject(raw) } catch (_: Exception) {
            throw RuntimeException(
                "Anthropic returned an unreadable response. Please try again."
            )
        }
        val block = obj.optJSONArray("content")?.optJSONObject(0)
        val content = block?.optString("text", "").orEmpty()
        val usageObj = obj.optJSONObject("usage")
        val total = if (usageObj != null) {
            usageObj.optLong("input_tokens", 0) + usageObj.optLong("output_tokens", 0)
        } else null
        return ParsedCompletion(content, total)
    }

    // ── shared helpers ─────────────────────────────────────────────────────────

    private fun retryWaitMillis(retryAfter: String?, reset: String?, message: String?): Long =
        RetryDelayParser.retryAfterMillis(retryAfter)
            ?: RetryDelayParser.durationMillis(reset)
            ?: RetryDelayParser.durationMillis(message)
            ?: 60_000L

    private suspend fun waitForConnection(
        attempt: Int,
        deadline: Long,
        error: IOException,
        providerName: String,
    ) {
        if (!hasValidatedInternet()) {
            while (System.currentTimeMillis() < deadline && !hasValidatedInternet()) {
                val remainingMinutes =
                    ((deadline - System.currentTimeMillis()).coerceAtLeast(0) / 60_000L) + 1
                onNetworkStatus(
                    "Internet connection lost. Focal is waiting and will keep retrying " +
                        "for about $remainingMinutes more minute${if (remainingMinutes == 1L) "" else "s"}."
                )
                delay(NETWORK_POLL_MS)
            }
            if (!hasValidatedInternet()) {
                throw RuntimeException(
                    "The phone stayed offline too long, so Focal could not reach " +
                        "$providerName.", error
                )
            }
            onNetworkStatus("Internet is back. Reconnecting to $providerName…")
            delay(750)
            return
        }

        val seconds = min(60L, 1L shl min(attempt, 6))
        val reason = when (error) {
            is UnknownHostException -> "DNS lookup failed"
            is SocketTimeoutException -> "connection timed out"
            else -> "connection was interrupted"
        }
        onNetworkStatus(
            "$providerName $reason. Retrying automatically in ${seconds}s " +
                "(attempt ${attempt + 1})."
        )
        delay(seconds * 1000L)
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isTransientNetworkError(error: IOException): Boolean =
        error is UnknownHostException ||
            error is ConnectException ||
            error is NoRouteToHostException ||
            error is SocketTimeoutException ||
            error is SocketException

    private fun friendlyNetworkError(error: IOException): String = when (error) {
        is UnknownHostException ->
            "Focal could not resolve the API server. It kept retrying, but the phone's " +
                "internet or DNS connection did not recover. Check Wi-Fi/mobile data and try again."
        is SocketTimeoutException ->
            "The connection timed out after automatic retries. Check the connection and try again."
        else ->
            "The connection was interrupted after automatic retries: " +
                (error.message ?: error.javaClass.simpleName)
    }

    private data class ParsedCompletion(
        val text: String,
        val usageTokens: Long? = null,
    )

    companion object {
        private const val NETWORK_RETRY_WINDOW_MS = 10L * 60L * 1000L
        private const val NETWORK_POLL_MS = 2_000L
        private const val MAX_RATE_RETRIES = 8
        private const val MAX_RATE_WAIT_MS = 10L * 60L * 1_000L
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

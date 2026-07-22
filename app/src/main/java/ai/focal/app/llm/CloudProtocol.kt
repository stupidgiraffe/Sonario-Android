package ai.focal.app.llm

import ai.focal.app.SensitiveDataRedactor
import org.json.JSONArray
import org.json.JSONObject

/** Pure request serialization shared by the Android transport and unit tests. */
internal object CloudRequestPayloads {
    fun openAiCompatible(
        config: ProviderConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", config.temperature)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .apply {
                if (ProviderCapabilities.usesGroqQwenPolicy(config)) {
                    put("top_p", 0.8)
                    put("reasoning_effort", "none")
                    put("reasoning_format", "hidden")
                    put("stream_options", JSONObject().put("include_usage", true))
                }
            }
            .toString()
    }

    fun anthropic(
        config: ProviderConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String = JSONObject()
        .put("model", config.model)
        .put("system", system)
        .put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", user)),
        )
        .put("temperature", config.temperature)
        .put("max_tokens", maxTokens.coerceIn(1, 8192))
        .put("stream", true)
        .toString()
}

/** Converts untrusted provider failures into bounded, credential-safe UI text. */
internal object CloudErrorMapper {
    private const val MAX_PROVIDER_ERROR_CHARS = 500

    fun apiError(obj: JSONObject, providerName: String): String? {
        val error = obj.optJSONObject("error") ?: return null
        val message = safeProviderMessage(error.optString("message"))
        val type = safeProviderMessage(error.optString("type"))
        return when {
            message.isNotBlank() -> "$providerName: $message"
            type.isNotBlank() -> "$providerName: $type"
            else -> "$providerName returned an API error."
        }
    }

    fun apiMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")
            ?.let(::safeProviderMessage)
    } catch (_: Exception) {
        null
    }

    fun friendlyHttpError(code: Int, body: String, providerName: String): String {
        val message = try {
            val obj = JSONObject(body)
            obj.optJSONObject("error")?.optString("message")?.trim()
                ?: obj.optJSONObject("error")?.optString("type")?.trim()
        } catch (_: Exception) {
            null
        }?.let(::safeProviderMessage)
        return when (code) {
            400 -> "$providerName rejected this request" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
            401 -> "$providerName rejected the API key. Check it in Settings → Providers."
            403 -> "$providerName denied this request. Check the API key and model access."
            404 -> "The selected model was not found. Choose a current model in Settings."
            413 -> "This source is too large for one request. Try a shorter source."
            429 -> "$providerName's rate limit is still active after repeated waits. " +
                "Try again later." + (if (!message.isNullOrBlank()) ": $message" else ".")
            in 500..599 ->
                "$providerName had a server error after automatic retries. Try again shortly."
            else -> "$providerName request failed ($code)" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
        }
    }

    private fun safeProviderMessage(message: String): String =
        SensitiveDataRedactor.redact(message.trim(), MAX_PROVIDER_ERROR_CHARS)
}

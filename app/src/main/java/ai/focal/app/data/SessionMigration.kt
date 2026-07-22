package ai.focal.app.data

import ai.focal.app.llm.LlmProvider
import ai.focal.app.llm.RateLimiter

/** Pure, deterministic migration for the provider fields stored in session JSON. */
internal object SessionMigration {
    data class Result(
        val engineChoice: EngineChoice,
        val cloudProviderId: String,
        val cloudModel: String,
        val issue: String? = null,
    )

    fun migrate(
        schema: Int,
        engineChoice: String?,
        cloudProviderId: String?,
        cloudModel: String?,
        legacyGroqModel: String?,
    ): Result {
        val normalizedEngine = engineChoice?.trim()?.uppercase().orEmpty()
        val engine = when (normalizedEngine) {
            "ON_DEVICE" -> EngineChoice.ON_DEVICE
            "CLOUD", "GROQ" -> EngineChoice.CLOUD
            "" -> EngineChoice.CLOUD // Both schema 1 and 2 defaulted to cloud.
            else -> EngineChoice.ON_DEVICE
        }
        val engineIssue = normalizedEngine
            .takeIf { it.isNotEmpty() && it !in setOf("ON_DEVICE", "CLOUD", "GROQ") }
            ?.let { "Saved session uses unsupported engine '$it'." }

        // Schema 1 was Groq-only. Schema 2 introduced this field but its
        // absence still represented the Groq default. Preserve unknown nonblank
        // IDs so the caller can report them rather than silently switching.
        val providerId = cloudProviderId?.trim().orEmpty().ifBlank { LlmProvider.GROQ.id }
        val providerIssue = providerId
            .takeIf { LlmProvider.fromIdOrNull(it) == null }
            ?.let { "Saved session uses unsupported cloud provider '$it'." }

        val storedModel = cloudModel?.trim().orEmpty().ifBlank {
            legacyGroqModel?.trim().orEmpty()
        }.ifBlank {
            if (schema <= LEGACY_GROQ_SCHEMA && engine == EngineChoice.CLOUD) {
                RateLimiter.GROQ_QWEN_MODEL
            } else {
                ""
            }
        }
        val model = LlmProvider.fromIdOrNull(providerId)?.let { provider ->
            Settings.migrateModelId(provider, storedModel)
        } ?: storedModel

        return Result(
            engineChoice = engine,
            cloudProviderId = providerId,
            cloudModel = model,
            issue = engineIssue ?: providerIssue,
        )
    }

    private const val LEGACY_GROQ_SCHEMA = 1
}

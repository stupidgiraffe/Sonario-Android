package ai.sonario.app.llm

import java.io.File

data class ModelInfo(
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val contextTokens: Int,
    val note: String,
    val downloadUrl: String,
    val present: Boolean = false,
) {
    /** Friendly decimal-MB size, rounded to the nearest 10 MB for display. */
    val sizeMb: Int
        get() = (((sizeBytes + 5_000_000L) / 10_000_000L) * 10L).toInt()
}

/**
 * Mobile-oriented local models. Exact byte counts come from the public model
 * repositories and are used to reject truncated or oversized downloads.
 */
val BUNDLED_MODELS = listOf(
    ModelInfo(
        label = "Qwen3 4B Instruct 2507",
        fileName = "qwen3-4b-instruct-2507-q4_k_m.gguf",
        sizeBytes = 2_497_280_736L,
        contextTokens = 4096,
        note = "Best overall. Strong summaries and follow-up answers; larger and slower than LFM2.",
        downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/" +
            "resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true",
    ),
    ModelInfo(
        label = "Gemma 3n E4B Instruct",
        fileName = "gemma-3n-e4b-it-q4_k_m.gguf",
        sizeBytes = 4_237_063_776L,
        contextTokens = 4096,
        note = "Best for nuanced long-form summaries. Mobile-designed, but the largest and slowest option.",
        downloadUrl = "https://huggingface.co/second-state/gemma-3n-E4B-it-GGUF/" +
            "resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf?download=true",
    ),
    ModelInfo(
        label = "LFM2 2.6B",
        fileName = "lfm2-2.6b-q4_k_m.gguf",
        sizeBytes = 1_563_668_704L,
        contextTokens = 4096,
        note = "Fastest and smallest. Good for quick summaries; less capable with subtle material.",
        downloadUrl = "https://huggingface.co/LiquidAI/LFM2-2.6B-GGUF/" +
            "resolve/main/LFM2-2.6B-Q4_K_M.gguf?download=true",
    ),
)

/** Legacy entries remain resolvable only for durable sessions that still use them. */
internal val LEGACY_MODELS = listOf(
    ModelInfo(
        label = "Qwen2.5 1.5B Instruct (legacy)",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 0L,
        contextTokens = 4096,
        note = "Retained for an existing local session.",
        downloadUrl = "",
    ),
    ModelInfo(
        label = "Llama 3.2 3B Instruct (legacy)",
        fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
        sizeBytes = 0L,
        contextTokens = 4096,
        note = "Retained for an existing local session.",
        downloadUrl = "",
    ),
    ModelInfo(
        label = "Phi-3.5 Mini Instruct (legacy)",
        fileName = "phi-3.5-mini-instruct-q4_k_m.gguf",
        sizeBytes = 0L,
        contextTokens = 4096,
        note = "Retained for an existing local session.",
        downloadUrl = "",
    ),
)

internal val ALL_LOCAL_MODELS = BUNDLED_MODELS + LEGACY_MODELS

data class LegacyCleanupResult(
    val deleted: Set<String>,
    val failed: Set<String>,
)

/** Deletes only known obsolete payloads that no durable session still references. */
object LegacyModelCleanup {
    fun clean(modelsDir: File, protectedFileNames: Set<String>): LegacyCleanupResult {
        if (!modelsDir.isDirectory) return LegacyCleanupResult(emptySet(), emptySet())
        val deleted = linkedSetOf<String>()
        val failed = linkedSetOf<String>()

        LEGACY_MODELS.forEach { model ->
            val candidates = buildList {
                add(File(modelsDir, model.fileName + ".part"))
                if (model.fileName !in protectedFileNames) {
                    add(File(modelsDir, model.fileName))
                }
            }
            candidates.filter(File::exists).forEach { file ->
                if (file.isFile && file.delete()) deleted += file.name else failed += file.name
            }
        }
        return LegacyCleanupResult(deleted, failed)
    }
}

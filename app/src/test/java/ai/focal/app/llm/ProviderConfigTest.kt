package ai.focal.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {
    @Test
    fun `stable provider ids resolve without fallback ambiguity`() {
        LlmProvider.entries.forEach { provider ->
            assertEquals(provider, LlmProvider.fromIdOrNull(provider.id))
        }
        assertNull(LlmProvider.fromIdOrNull("removed-provider"))
        assertEquals(LlmProvider.GROQ, LlmProvider.fromId("removed-provider"))
    }

    @Test
    fun `built in provider configuration accepts its default endpoint`() {
        LlmProvider.entries.filter { it != LlmProvider.CUSTOM }.forEach { provider ->
            val model = provider.suggestedModels.first()
            assertNull(ProviderConfig(provider, model).validationError())
        }
    }

    @Test
    fun `custom endpoint requires model and absolute http url`() {
        assertTrue(
            ProviderConfig(LlmProvider.CUSTOM, "").validationError()!!.contains("model")
        )
        assertTrue(
            ProviderConfig(LlmProvider.CUSTOM, "model").validationError()!!.contains("base URL")
        )
        assertTrue(
            ProviderConfig(LlmProvider.CUSTOM, "model", "example.com/v1")
                .validationError()!!.contains("http")
        )
        assertNull(
            ProviderConfig(LlmProvider.CUSTOM, "model", "https://example.com/v1")
                .validationError()
        )
    }

    @Test
    fun `endpoint rejects embedded credentials query and fragment`() {
        val unsafe = listOf(
            "https://user:secret@example.com/v1" to "credentials",
            "https://example.com/v1?token=secret" to "query",
            "https://example.com/v1#chat" to "query",
        )
        unsafe.forEach { (url, expected) ->
            assertTrue(
                ProviderConfig(LlmProvider.CUSTOM, "model", url)
                    .validationError()!!.contains(expected)
            )
        }
    }

    @Test
    fun `cleartext endpoints are restricted to device loopback`() {
        listOf("localhost", "127.0.0.1", "[::1]").forEach { host ->
            assertNull(
                ProviderConfig(LlmProvider.CUSTOM, "model", "http://$host:11434/v1")
                    .validationError()
            )
        }
        assertTrue(
            ProviderConfig(LlmProvider.CUSTOM, "model", "http://192.168.1.10:11434/v1")
                .validationError()!!.contains("https")
        )
        assertTrue(
            ProviderConfig(LlmProvider.CUSTOM, "model", "http://example.com/v1")
                .validationError()!!.contains("https")
        )
    }
}

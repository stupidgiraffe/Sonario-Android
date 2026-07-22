package ai.focal.app.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProtocolTest {
    @Test
    fun `OpenAI compatible payload preserves roles and generic provider fields`() {
        val config = ProviderConfig(
            provider = LlmProvider.CUSTOM,
            model = "custom-model",
            customBaseUrl = "https://example.com/v1",
            temperature = 0.42f,
        )

        val payload = JSONObject(
            CloudRequestPayloads.openAiCompatible(config, "system text", "user text", 1234)
        )
        val messages = payload.getJSONArray("messages")

        assertEquals("custom-model", payload.getString("model"))
        assertEquals(1234, payload.getInt("max_tokens"))
        assertTrue(payload.getBoolean("stream"))
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("system text", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("user text", messages.getJSONObject(1).getString("content"))
        assertFalse(payload.has("reasoning_effort"))
    }

    @Test
    fun `Groq Qwen payload adds only its verified capability fields`() {
        val payload = JSONObject(
            CloudRequestPayloads.openAiCompatible(
                ProviderConfig(LlmProvider.GROQ, RateLimiter.GROQ_QWEN_MODEL),
                "system",
                "user",
                4096,
            )
        )

        assertEquals("none", payload.getString("reasoning_effort"))
        assertEquals("hidden", payload.getString("reasoning_format"))
        assertTrue(payload.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun `Anthropic payload uses native system and messages shape with bounded output`() {
        val config = ProviderConfig(LlmProvider.ANTHROPIC, "claude-test", temperature = 0.25f)
        val payload = JSONObject(
            CloudRequestPayloads.anthropic(config, "system text", "user text", 20_000)
        )
        val message = payload.getJSONArray("messages").getJSONObject(0)

        assertEquals("claude-test", payload.getString("model"))
        assertEquals("system text", payload.getString("system"))
        assertEquals("user", message.getString("role"))
        assertEquals("user text", message.getString("content"))
        assertEquals(8192, payload.getInt("max_tokens"))
        assertFalse(payload.has("top_p"))
    }

    @Test
    fun `provider error mapping hides credentials and keeps auth failures actionable`() {
        val echoedKey = "sk-provider-secret-1234"
        val rejected = CloudErrorMapper.friendlyHttpError(
            400,
            """{"error":{"message":"bad api_key=$echoedKey"}}""",
            "Custom",
        )
        val unauthorized = CloudErrorMapper.friendlyHttpError(
            401,
            """{"error":{"message":"$echoedKey"}}""",
            "OpenAI",
        )

        assertFalse(rejected.contains(echoedKey))
        assertTrue(rejected.contains("[REDACTED]"))
        assertFalse(unauthorized.contains(echoedKey))
        assertTrue(unauthorized.contains("Settings"))
    }
}

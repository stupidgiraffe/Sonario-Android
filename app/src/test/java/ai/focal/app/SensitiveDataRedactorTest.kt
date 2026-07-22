package ai.focal.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redacts headers json fields query values and common key formats`() {
        val secrets = listOf(
            "secret-bearer-value",
            "secret-json-value",
            "secret-query-value",
            "sk-provider-secret-1234",
            "gsk_provider_secret_1234",
        )
        val raw = """
            Authorization: Bearer ${secrets[0]}
            {"api_key":"${secrets[1]}"}
            https://example.com/v1?token=${secrets[2]}
            provider said ${secrets[3]} and ${secrets[4]}
        """.trimIndent()

        val safe = SensitiveDataRedactor.redact(raw)

        secrets.forEach { secret -> assertFalse(safe.contains(secret)) }
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test
    fun `redacts URL user info and caps untrusted error text`() {
        val safe = SensitiveDataRedactor.redact(
            "failed at https://user:password@example.com/" + "x".repeat(100),
            maxChars = 40,
        )

        assertFalse(safe.contains("user:password"))
        assertTrue(safe.endsWith("…"))
        assertTrue(safe.length == 41)
    }
}

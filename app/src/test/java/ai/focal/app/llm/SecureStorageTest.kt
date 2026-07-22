package ai.focal.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureStorageTest {
    @Test
    fun `masked credential never exposes short secret`() {
        assertEquals("Not set", SecureStorage.masked(null))
        assertEquals("••••••", SecureStorage.masked("short-key"))
    }

    @Test
    fun `masked credential reveals only bounded prefix and suffix`() {
        assertEquals("sk-a…x7Q", SecureStorage.masked("sk-a-secret-value-x7Q"))
    }
}

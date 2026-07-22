package ai.sonario.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var modelsDir: java.io.File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsDir = temporaryFolder.newFolder("models")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `resumes a valid partial response and finalizes exact bytes`() = runBlocking {
        val expected = "0123456789".toByteArray()
        java.io.File(modelsDir, "model.gguf.part").writeBytes(expected.copyOfRange(0, 4))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody(okio.Buffer().write(expected, 4, 6))
        )

        val states = downloader().download(
            model(expected.size.toLong(), expectedBytes = expected)
        ).toList()

        assertTrue(states.last() is ModelDownloader.State.Done)
        assertArrayEquals(expected, java.io.File(modelsDir, "model.gguf").readBytes())
        assertEquals("bytes=4-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `restarts when server ignores range instead of appending`() = runBlocking {
        val expected = "0123456789".toByteArray()
        java.io.File(modelsDir, "model.gguf.part").writeText("0123")
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(expected)))

        val states = downloader().download(
            model(expected.size.toLong(), expectedBytes = expected)
        ).toList()

        assertTrue(states.last() is ModelDownloader.State.Done)
        assertArrayEquals(expected, java.io.File(modelsDir, "model.gguf").readBytes())
    }

    @Test
    fun `retains short partial and does not finalize it`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("short", 2))

        val states = downloader().download(model(10)).toList()

        assertTrue(states.last() is ModelDownloader.State.Failed)
        assertEquals(5, java.io.File(modelsDir, "model.gguf.part").length())
        assertFalse(java.io.File(modelsDir, "model.gguf").exists())
    }

    @Test
    fun `rejects mismatched content range without changing partial`() = runBlocking {
        val part = java.io.File(modelsDir, "model.gguf.part").apply { writeText("0123") }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-9/10")
                .setBody("56789")
        )

        val states = downloader().download(model(10)).toList()

        assertTrue(states.last() is ModelDownloader.State.Failed)
        assertEquals("0123", part.readText())
    }

    @Test
    fun `fails before network when storage is insufficient`() = runBlocking {
        val states = downloader(usableBytes = 9).download(model(10)).toList()

        assertTrue(states.last() is ModelDownloader.State.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancellation retains a resumable partial`() = runBlocking {
        val expected = ByteArray(1024 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setChunkedBody(okio.Buffer().write(expected), 64 * 1024)
                .throttleBody(64 * 1024L, 25, java.util.concurrent.TimeUnit.MILLISECONDS)
        )

        downloader().download(model(expected.size.toLong()))
            .first { it is ModelDownloader.State.Progress }

        val partialBytes = java.io.File(modelsDir, "model.gguf.part").length()
        assertTrue(partialBytes in 1 until expected.size.toLong())
        assertFalse(java.io.File(modelsDir, "model.gguf").exists())
    }

    @Test
    fun `rejects path traversal before creating files`() = runBlocking {
        val states = downloader().download(model(10, "../outside.gguf")).toList()

        assertTrue(states.last() is ModelDownloader.State.Failed)
        assertFalse(java.io.File(modelsDir.parentFile, "outside.gguf").exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `removes a full length corrupted partial`() = runBlocking {
        java.io.File(modelsDir, "model.gguf.part").writeText("corrupted!")

        val states = downloader().download(model(10, expectedBytes = "0123456789".toByteArray()))
            .toList()

        assertTrue(states.last() is ModelDownloader.State.Failed)
        assertFalse(java.io.File(modelsDir, "model.gguf.part").exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rejects duplicate concurrent download`() = runBlocking {
        val expected = ByteArray(1024 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setChunkedBody(okio.Buffer().write(expected), 64 * 1024)
                .throttleBody(64 * 1024L, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        val downloader = downloader()
        val first = async(Dispatchers.IO) {
            downloader.download(model(expected.size.toLong(), expectedBytes = expected)).toList()
        }
        assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))

        val duplicate = downloader.download(
            model(expected.size.toLong(), expectedBytes = expected)
        ).toList()

        assertTrue(duplicate.last() is ModelDownloader.State.Failed)
        assertTrue((duplicate.last() as ModelDownloader.State.Failed).message.contains("already"))
        first.cancelAndJoin()
    }

    private fun downloader(usableBytes: Long = Long.MAX_VALUE) = ModelDownloader(
        modelsDir = modelsDir,
        http = OkHttpClient(),
        usableSpace = { usableBytes },
        storageHeadroomBytes = 0,
    )

    private fun model(
        sizeBytes: Long,
        fileName: String = "model.gguf",
        expectedBytes: ByteArray = ByteArray(sizeBytes.toInt()) { (it % 251).toByte() },
    ) = ModelInfo(
        label = "Test model",
        fileName = fileName,
        sizeBytes = sizeBytes,
        sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(expectedBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        contextTokens = 4096,
        note = "test",
        downloadUrl = server.url("/model.gguf").toString(),
    )
}

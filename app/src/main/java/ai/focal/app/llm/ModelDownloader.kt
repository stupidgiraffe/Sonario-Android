package ai.focal.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads a GGUF model into Focal's private model directory.
 *
 * A completed file is accepted only when it matches the catalog's exact byte
 * count. Interrupted transfers remain as `.part` files and are resumed only
 * after the server proves that its range begins at the expected offset.
 */
class ModelDownloader(
    private val modelsDir: File,
    private val http: OkHttpClient = defaultHttpClient(),
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val storageHeadroomBytes: Long = STORAGE_HEADROOM_BYTES,
) {

    sealed interface State {
        data class Progress(val bytes: Long, val total: Long) : State {
            val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
        }
        data class Done(val file: File) : State
        data class Failed(val message: String) : State
    }

    /**
     * Stream download states. Cancellation closes the response and leaves a
     * validated partial file in place for a later retry.
     */
    fun download(model: ModelInfo): Flow<State> = flow {
        if (!isSafeFileName(model.fileName)) {
            emit(State.Failed("The model catalog contains an invalid file name."))
            return@flow
        }
        if (model.sizeBytes <= 0L) {
            emit(State.Failed("The model catalog does not contain a valid download size."))
            return@flow
        }
        if (!SHA256.matches(model.sha256)) {
            emit(State.Failed("The model catalog does not contain a valid checksum."))
            return@flow
        }
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            emit(State.Failed("Focal could not create its private model directory."))
            return@flow
        }

        val target = File(modelsDir, model.fileName)
        if (target.exists()) {
            if (target.isFile && target.length() == model.sizeBytes &&
                sha256(target) == model.sha256
            ) {
                emit(State.Done(target))
            } else {
                emit(
                    State.Failed(
                        "The installed model file has an unexpected size. " +
                            "Remove it before downloading a fresh copy."
                    )
                )
            }
            return@flow
        }

        val activeKey = target.absolutePath
        if (!activeDownloads.add(activeKey)) {
            emit(State.Failed("This model is already downloading."))
            return@flow
        }

        try {
            val part = File(modelsDir, model.fileName + PART_SUFFIX)
            var offset = part.takeIf { it.isFile }?.length() ?: 0L
            if (offset == model.sizeBytes) {
                if (sha256(part) != model.sha256) {
                    part.delete()
                    emit(
                        State.Failed(
                            "The partial model failed its integrity check and was removed. " +
                                "Tap Retry to download a fresh copy."
                        )
                    )
                    return@flow
                }
                finalize(part, target)
                emit(State.Progress(model.sizeBytes, model.sizeBytes))
                emit(State.Done(target))
                return@flow
            }
            if (offset > model.sizeBytes) offset = 0L

            val bytesRemaining = model.sizeBytes - offset
            val requiredBytes = safeAdd(bytesRemaining, storageHeadroomBytes)
            val freeBytes = usableSpace(modelsDir)
            if (freeBytes < requiredBytes) {
                emit(
                    State.Failed(
                        "Not enough free storage for ${model.label}. " +
                            "${formatMb(requiredBytes)} MB is needed, but " +
                            "${formatMb(freeBytes)} MB is available."
                    )
                )
                return@flow
            }

            val request = Request.Builder()
                .url(model.downloadUrl)
                .header("User-Agent", "Focal/1.0")
                .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                .build()

            http.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    emit(
                        State.Failed(
                            "Model server returned ${response.code}. " +
                                "Check the connection and try again."
                        )
                    )
                    return@flow
                }
                val body = response.body ?: run {
                    emit(State.Failed("The model server returned an empty response."))
                    return@flow
                }

                if (response.code == 206) {
                    val contentRange = parseContentRange(response.header("Content-Range"))
                    if (contentRange == null || contentRange.start != offset ||
                        contentRange.total != model.sizeBytes
                    ) {
                        emit(
                            State.Failed(
                                "The model server returned an invalid resume range. " +
                                    "The partial download was kept for retry."
                            )
                        )
                        return@flow
                    }
                    val declaredLength = body.contentLength()
                    val rangeLength = contentRange.end - contentRange.start + 1L
                    if (contentRange.end < contentRange.start ||
                        contentRange.end >= model.sizeBytes ||
                        (declaredLength >= 0L && declaredLength != rangeLength)
                    ) {
                        emit(State.Failed("The model server returned inconsistent range metadata."))
                        return@flow
                    }
                } else {
                    // A 200 response ignored Range, so restart instead of appending.
                    offset = 0L
                    val declaredLength = body.contentLength()
                    if (declaredLength >= 0L && declaredLength != model.sizeBytes) {
                        emit(State.Failed("The model server returned an unexpected file size."))
                        return@flow
                    }
                }

                RandomAccessFile(part, "rw").use { output ->
                    if (offset == 0L) output.setLength(0L)
                    output.seek(offset)
                    var written = offset
                    var lastEmit = offset

                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (written + count > model.sizeBytes) {
                                throw DownloadValidationException(
                                    "The model server sent more data than the catalog allows."
                                )
                            }
                            output.write(buffer, 0, count)
                            written += count
                            if (written - lastEmit >= PROGRESS_STEP_BYTES) {
                                emit(State.Progress(written, model.sizeBytes))
                                lastEmit = written
                            }
                        }
                    }
                    output.fd.sync()
                }

                if (part.length() != model.sizeBytes) {
                    emit(
                        State.Failed(
                            "The model download ended early. " +
                                "The partial file was kept; tap Retry to resume."
                        )
                    )
                    return@flow
                }

                if (sha256(part) != model.sha256) {
                    part.delete()
                    emit(
                        State.Failed(
                            "The downloaded model failed its integrity check and was removed. " +
                                "Tap Retry to download it again."
                        )
                    )
                    return@flow
                }

                finalize(part, target)
                emit(State.Progress(target.length(), model.sizeBytes))
                emit(State.Done(target))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emit(State.Failed(error.message ?: "Model download failed."))
        } finally {
            activeDownloads.remove(activeKey)
        }
    }.flowOn(Dispatchers.IO)

    fun deletePartial(model: ModelInfo) {
        if (isSafeFileName(model.fileName)) {
            File(modelsDir, model.fileName + PART_SUFFIX).delete()
        }
    }

    private fun finalize(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private class DownloadValidationException(message: String) : Exception(message)

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val STORAGE_HEADROOM_BYTES = 256L * 1_000_000L
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private const val BUFFER_SIZE_BYTES = 128 * 1024
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        private fun isSafeFileName(fileName: String): Boolean =
            fileName.isNotBlank() && fileName == File(fileName).name &&
                !fileName.contains('/') && !fileName.contains('\\')

        private fun parseContentRange(value: String?): ContentRange? {
            val match = value?.let(CONTENT_RANGE::matchEntire) ?: return null
            val (start, end, total) = match.destructured
            return ContentRange(start.toLong(), end.toLong(), total.toLong())
        }

        private fun safeAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        private fun formatMb(bytes: Long): Long =
            (bytes.coerceAtLeast(0L) + 999_999L) / 1_000_000L
    }
}

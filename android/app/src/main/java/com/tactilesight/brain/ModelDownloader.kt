package com.tactilesight.brain

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Fetches a small GGUF vision model into the llama_cpp engine directory, so a
 * phone nobody has `adb push`ed to can still describe offline.
 *
 * This reverses ADR-0010's "never downloaded" for one case only. That rule was
 * about the venue: a multi-GB pull on hall WiFi kept dying. A stranger
 * installing the APK at home has no adb, no QAIRT bundle for their SoC, and
 * usually decent WiFi — sideloading is not an option for them at all.
 *
 * Qwen3-VL-**2B** rather than the 4B the NPU runs: ~1.5 GB instead of ~3 GB,
 * and it fits beside the OS on a 6 GB phone. It is the same family, so
 * [VlmPrompt] — tuned on the 4B — is the closest prompt we have for it; it has
 * **not** been benchmarked on the 2B.
 *
 * Files are written as `<name>.part` and renamed on completion. [ModelStore]
 * only recognises `.gguf`, so a half-finished download is never mistaken for a
 * bundle, and an interrupted one resumes from where it stopped.
 */
class ModelDownloader(private val targetDir: File) {

    sealed interface State {
        data object Idle : State
        data class Running(val doneBytes: Long, val totalBytes: Long) : State
        data object Done : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once every file is in place under its final name. */
    fun isComplete(): Boolean = FILES.all { File(targetDir, it.name).length() == it.bytes }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()
            val remaining = FILES.sumOf { it.bytes - existingBytes(it) }
            val free = StatFs(targetDir.path).availableBytes
            if (free < remaining + HEADROOM_BYTES) {
                _state.value = State.Failed(
                    "Not enough space: needs ${remaining.toGb()} GB, ${free.toGb()} GB free",
                )
                return@withContext false
            }

            var done = FILES.sumOf { existingBytes(it) }
            _state.value = State.Running(done, TOTAL_BYTES)
            for (file in FILES) {
                fetch(file) { delta ->
                    done += delta
                    _state.value = State.Running(done, TOTAL_BYTES)
                }
            }
            _state.value = State.Done
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = State.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "model download failed", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun existingBytes(file: RemoteFile): Long {
        val final = File(targetDir, file.name)
        if (final.length() == file.bytes) return file.bytes
        return File(targetDir, file.name + PART_SUFFIX).length()
    }

    private suspend fun fetch(file: RemoteFile, progress: (Long) -> Unit) {
        val final = File(targetDir, file.name)
        if (final.length() == file.bytes) return

        val part = File(targetDir, file.name + PART_SUFFIX)
        val offset = part.length()
        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

        try {
            val append = when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                // The server ignored the range: start this file over rather
                // than appending a second copy onto the first part.
                HttpURLConnection.HTTP_OK -> {
                    progress(-offset)
                    false
                }
                else -> error("HTTP ${connection.responseCode} for ${file.name}")
            }

            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        progress(read.toLong())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        check(part.length() == file.bytes) {
            "${file.name} is ${part.length()} bytes, expected ${file.bytes}"
        }
        check(part.renameTo(final)) { "could not rename ${part.name}" }
        Log.i(TAG, "downloaded ${file.name}")
    }

    private data class RemoteFile(val name: String, val url: String, val bytes: Long)

    private fun Long.toGb(): String = "%.1f".format(this / 1e9)

    companion object {
        private const val TAG = "ModelDownloader"
        private const val PART_SUFFIX = ".part"
        private const val TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 256 * 1024

        /** Room left over for the OS and the model's own runtime allocations. */
        private const val HEADROOM_BYTES = 500L * 1024 * 1024

        private const val REPO = "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main"

        /**
         * Exact sizes, so a truncated file is caught rather than handed to
         * llama.cpp. Read from the Hugging Face tree API on 2026-10-04; if the
         * repo re-uploads, these fail loudly and need updating.
         *
         * The projector keeps its `mmproj` prefix — that is how
         * [GenieXBrain] tells it from the weights.
         */
        private val FILES = listOf(
            RemoteFile(
                "Qwen3VL-2B-Instruct-Q4_K_M.gguf",
                "$REPO/Qwen3VL-2B-Instruct-Q4_K_M.gguf",
                MODEL_BYTES,
            ),
            RemoteFile(
                "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
                "$REPO/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf",
                MMPROJ_BYTES,
            ),
        )

        private const val MODEL_BYTES = 1_107_409_952L
        private const val MMPROJ_BYTES = 445_053_216L
        private val TOTAL_BYTES = FILES.sumOf { it.bytes }
    }
}

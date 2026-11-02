package moe.ouom.neriplayer.core.player.download.runtime

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.download.transfer.AudioDownloadFileTransfer
import moe.ouom.neriplayer.core.player.download.transfer.AudioDownloadTransferPolicy
import moe.ouom.neriplayer.core.player.download.transfer.shouldTryNextDownloadMirror
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.qqmusic.auth.QQMusicAuthBundle
import moe.ouom.neriplayer.data.traffic.TrafficByteAccumulator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QQMusicAudioDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `qq download request carries cookie referer and native user agent`() {
        val request = buildQQMusicAudioDownloadRequest(
            url = "https://stream.qqmusic.qq.com/F000song.flac",
            auth = QQMusicAuthBundle(
                cookies = mapOf(
                    "uin" to "10001",
                    "qm_keyst" to "session-key"
                )
            )
        )

        assertEquals("https://y.qq.com/", request.header("Referer"))
        assertEquals("QQMusic 14090008(android 10)", request.header("User-Agent"))
        assertEquals("uin=10001; qm_keyst=session-key", request.header("Cookie"))
    }

    @Test
    fun `mirror fallback only advances for retryable transport failures`() {
        assertTrue(shouldTryNextDownloadMirror(IOException("HTTP 403"), 0, 2))
        assertFalse(shouldTryNextDownloadMirror(IOException("HTTP 403"), 1, 2))
        assertFalse(
            shouldTryNextDownloadMirror(
                java.util.concurrent.CancellationException("cancelled"),
                0,
                2
            )
        )
        assertFalse(shouldTryNextDownloadMirror(IllegalArgumentException("bad request"), 0, 2))
    }

    @Test
    fun `qq source rejection triggers a fresh vkey resolve instead of a terminal failure`() {
        val forbidden = IOException("HTTP 403")

        assertFalse(
            AudioDownloadTransferPolicy.shouldRetryDownloadFailureForSource(
                error = forbidden,
                isYouTubeMusic = false
            )
        )
        assertTrue(AudioDownloadTransferPolicy.shouldRetryQQMusicDownloadFailure(forbidden))
        assertTrue(
            AudioDownloadTransferPolicy.shouldRetryQQMusicDownloadFailure(
                IOException("HTTP 416")
            )
        )
        assertFalse(
            AudioDownloadTransferPolicy.shouldRetryQQMusicDownloadFailure(
                java.util.concurrent.CancellationException("cancelled")
            )
        )
    }

    @Test
    fun `first mirror failure restarts download from the next mirror`() = runBlocking {
        val destFile = temporaryFolder.newFile("qq-mirror.bin")
        val visitedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val host = chain.request().url.host
                visitedHosts += host
                if (host == "one.stream.qqmusic.qq.com") {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("blocked".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("audio-bytes".toResponseBody("audio/mpeg".toMediaType()))
                        .build()
                }
            }
            .build()
        val transfer = AudioDownloadFileTransfer(
            hooks = NoOpTransferHooks(),
            readBufferBytes = 64L * 1024L,
            preferredChunkSizeBytes = 1024L * 1024L
        )
        val primary = Request.Builder()
            .url("https://one.stream.qqmusic.qq.com/F000song.flac")
            .build()
        val mirror = Request.Builder()
            .url("https://two.stream.qqmusic.qq.com/F000song.flac")
            .build()

        val result = transfer.download(
            client = client,
            request = primary,
            destFile = destFile,
            displayFileName = destFile.name,
            songId = 42L,
            songKey = "qq:42",
            fallbackRequests = listOf(mirror)
        )

        assertEquals(listOf("one.stream.qqmusic.qq.com", "two.stream.qqmusic.qq.com"), visitedHosts)
        assertEquals("audio-bytes", destFile.readText())
        assertEquals(11L, result.actualBytes)
    }

    private class NoOpTransferHooks : AudioDownloadFileTransfer.Hooks {
        override fun ensureDownloadNotCancelled(
            songId: Long,
            songKey: String,
            destFile: File,
            batchSessionId: Long?,
            attemptId: Long?,
            operationId: String?
        ) = Unit

        override fun <T> withWorkingFileMutation(
            songKey: String,
            stage: String,
            batchSessionId: Long?,
            attemptId: Long?,
            operationId: String?,
            block: () -> T
        ): T = block()

        override fun deleteWorkingFile(file: File?) {
            file?.delete()
        }

        override fun storageSpaceOwnerKey(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            file: File
        ): String = "test:${file.name}"

        override fun <T> executeTrackedCall(
            client: OkHttpClient,
            request: Request,
            songKey: String,
            operationId: String?,
            block: (Response) -> T
        ): T = client.newCall(request).execute().use(block)

        override fun newTrafficAccumulator(): TrafficByteAccumulator =
            TrafficByteAccumulator(onFlush = {})

        override fun markTransferNetworkActivity(
            operationId: String?,
            attemptId: Long?,
            songKey: String,
            transferGeneration: Long?
        ) = Unit

        override fun publishProgress(progress: DownloadProgress) = Unit

        override fun resolveVisibleDownloadFileName(
            requestedName: String,
            actualName: String
        ): String = requestedName
    }
}

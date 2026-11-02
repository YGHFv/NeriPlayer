package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class LibraryQQMusicLoadResult(
    val playlists: List<QQMusicPlaylist>,
    val warning: String? = null
)

internal class LibraryQQMusicRefreshOwner(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LibraryUiState>,
    private val load: suspend () -> LibraryQQMusicLoadResult
) {
    private var refreshJob: Job? = null
    private var pending = false

    fun onSessionChanged() {
        clear()
        refresh()
    }

    fun refresh() {
        if (!scope.isActive) return
        if (refreshJob?.isActive == true) {
            pending = true
            return
        }
        pending = false
        val nextJob = scope.launch(start = CoroutineStart.LAZY) { loadAndPublish() }
        refreshJob = nextJob
        state.update { it.copy(qqMusicRefreshing = true, qqMusicError = null) }
        nextJob.start()
    }

    fun clear() {
        val previousJob = refreshJob
        refreshJob = null
        pending = false
        previousJob?.cancel()
        state.update {
            it.copy(
                qqMusicPlaylists = emptyList(),
                qqMusicError = null,
                qqMusicWarning = null,
                qqMusicRefreshing = false
            )
        }
    }

    private suspend fun loadAndPublish() {
        try {
            val result = load()
            currentCoroutineContext().ensureActive()
            state.update {
                it.copy(
                    qqMusicPlaylists = result.playlists,
                    qqMusicError = null,
                    qqMusicWarning = result.warning
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state.update {
                it.copy(
                    qqMusicPlaylists = emptyList(),
                    qqMusicError = error.message,
                    qqMusicWarning = null
                )
            }
        } finally {
            if (refreshJob === currentCoroutineContext()[Job]) {
                refreshJob = null
                state.update { it.copy(qqMusicRefreshing = false) }
                if (pending) {
                    pending = false
                    refresh()
                }
            }
        }
    }
}

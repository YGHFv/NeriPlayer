@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity

import moe.ouom.neriplayer.core.player.runtime.stats.shouldSkipDuplicateTrackEnd
import moe.ouom.neriplayer.core.player.runtime.stats.trackEndKeyForSong

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.buildBiliPartSong
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.LocalPlaylistPlaybackSource
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.lifecycle.clearUsbExclusiveInterruptedPlaybackIntent
import moe.ouom.neriplayer.core.player.lifecycle.prepareUsbExclusiveRouteForManualPlayback
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.core.player.network.PlaybackNetworkResumeOwner
import moe.ouom.neriplayer.core.player.lyrics.externalLyricBoundaryProgressIntervalMs
import moe.ouom.neriplayer.core.player.lyrics.isExternalBluetoothLyricCadenceActive
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.persistence.persistStateNow
import moe.ouom.neriplayer.core.player.persistence.prepareStatePersist
import moe.ouom.neriplayer.core.player.persistence.runSongMetadataMutation
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.PlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.USB_TRACK_TRANSITION_PROTECTION_FADE_DURATION_MS
import moe.ouom.neriplayer.core.player.policy.command.resolveEffectivePlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolveManagedPlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolveNoFadePlaybackStartPlan
import moe.ouom.neriplayer.core.player.policy.command.resolvePauseVolumePlan
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackContinuationStartPlan
import moe.ouom.neriplayer.core.player.policy.command.shouldPausePlaybackWhenToggling
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingMediaLoadEntryAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingPauseAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingPlayAction
import moe.ouom.neriplayer.core.player.policy.pending.resolvePendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.resolveSeekExecutionAction
import moe.ouom.neriplayer.core.player.policy.pending.PendingSeekAction
import moe.ouom.neriplayer.core.player.policy.pending.SeekExecutionAction
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMedia
import moe.ouom.neriplayer.core.player.policy.pending.shouldApplyResolvedMediaSideEffects
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_PROGRESS_STATS_UPDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.player.policy.progress.resolveProgressIntervalWithLyricBoundary
import moe.ouom.neriplayer.core.player.policy.progress.resolvePlaybackProgressUpdateIntervalMs
import moe.ouom.neriplayer.core.player.policy.progress.shouldRunPlaybackProgressUpdates
import moe.ouom.neriplayer.core.player.policy.skip.BiliSkipSegmentSource
import moe.ouom.neriplayer.core.player.presentation.skip.resolveBiliSkipSegmentPromptMessageRes
import moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.prefetch.cancelGenericUrlPrefetchUnlessReusableForSong
import moe.ouom.neriplayer.core.player.prefetch.cancelYouTubePrefetchForPlaybackDemand
import moe.ouom.neriplayer.core.player.prefetch.clearPlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.prefetch.kickoffYouTubePlaybackIntentWarmup
import moe.ouom.neriplayer.core.player.prefetch.replacePlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.url.cancelUrlRefreshIfNotReusableForPendingLoad
import moe.ouom.neriplayer.core.player.url.allowsCustomCacheKey
import moe.ouom.neriplayer.core.player.url.listenTogetherFallbackResult
import moe.ouom.neriplayer.core.player.url.listenTogetherPreferredQualityKey
import moe.ouom.neriplayer.core.player.url.mergeListenTogetherFallbackResult
import moe.ouom.neriplayer.core.player.url.resolveSongUrl
import moe.ouom.neriplayer.core.player.url.resolvePlaybackAudioInfoForListenTogetherStreamCandidate
import moe.ouom.neriplayer.core.player.url.synchronizeCachedPlaybackDescriptor
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForSeek
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.isPlaybackActuallyAdvancing
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.recordPlaybackRuntimeProgress
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackRuntimeWatchdog
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.platform.youtube.api.transport.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.data.ltw.playback.shouldShowListenTogetherPreviewClipNotice
import moe.ouom.neriplayer.core.player.host.PlayerFeedback
import kotlin.time.Duration.Companion.milliseconds

internal fun PlayerManager.stopPlaybackPreservingQueueImpl(clearMediaUrl: Boolean = false) {
    serverRecoveryPosition = null
    logQueueStopStart(clearMediaUrl)
    cancelPendingPauseRequest(resetVolumeToFull = true)
    clearPlaybackDemandCacheKey(reason = "stop_playback_preserving_queue")
    playbackRequestToken += 1
    cancelActivePlayForQueueStop()
    pendingMediaLoadActive = false
    cancelPlaybackStartupWatchdog(reason = "stop_playback_preserving_queue")
    clearActivePlaybackCandidates()
    cancelPrefetchForQueueStop()
    lastHandledTrackEndKey = null
    updateResumePlaybackRequested(false)
    PlaybackTransitionWakeLock.release(
        playbackRequestToken,
        "stop_playback_preserving_queue"
    )
    playbackTransportOwner.resetForRelease()
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(reason = "stop_playback_preserving_queue")
    persistCurrentLongFormPlaybackProgress()
    syncPlaybackStatsPlayingState(
        playing = false,
        reason = "stop_playback_preserving_queue"
    )
    stopPlayerForQueuePreservation()
    restoreQueueAfterStop(clearMediaUrl)
    consecutivePlayFailures = 0
    logQueueStopComplete()
    scheduleStatePersist()
}

private fun PlayerManager.logQueueStopStart(clearMediaUrl: Boolean) {
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): clearMediaUrl=$clearMediaUrl, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentSong=${currentSongNameForProgressLog()}, mediaUrlPresent=${hasQueueStopMediaUrl()}, stack=[${debugStackHint()}]"
    )
}

private fun PlayerManager.cancelActivePlayForQueueStop() {
    playJob?.cancel()
    playJob = null
}

private fun PlayerManager.cancelPrefetchForQueueStop() {
    currentYouTubePrefetchJob?.cancel()
    currentYouTubePrefetchJob = null
    currentYouTubePrefetchVideoIds = emptySet()
}

private fun PlayerManager.stopPlayerForQueuePreservation() {
    runCatching { player.playWhenReady = false }
    runCatching { player.stop() }
    runCatching { player.clearMediaItems() }
    _isPlayingFlow.value = false
    pauseLyriconIfEnabled()
    _playWhenReadyFlow.value = false
    _playerPlaybackStateFlow.value = Player.STATE_IDLE
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L
}

private fun PlayerManager.restoreQueueAfterStop(clearMediaUrl: Boolean) {
    if (currentPlaylist.isEmpty()) clearEmptyQueueAfterStop()
    else restoreNonemptyQueueAfterStop(clearMediaUrl)
}

private fun PlayerManager.clearEmptyQueueAfterStop() {
    clearRestoredPlayback()
    currentIndex = -1
    setCurrentSongForPlayback(null)
    clearQueueStopMediaUrl()
}

private fun PlayerManager.restoreNonemptyQueueAfterStop(clearMediaUrl: Boolean) {
    currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
    setCurrentSongForPlayback(currentPlaylist.getOrNull(currentIndex))
    if (clearMediaUrl) clearQueueStopMediaUrl()
}

private fun PlayerManager.clearQueueStopMediaUrl() {
    _currentMediaUrl.value = null
    _currentPlaybackAudioInfo.value = null
    currentMediaUrlResolvedAtMs = 0L
}

private fun PlayerManager.logQueueStopComplete() {
    NPLogger.d(
        "NERI-PlayerManager",
        "stopPlaybackPreservingQueue(): completed, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, retainedSong=${currentSongNameForProgressLog()}, mediaUrlPresent=${hasQueueStopMediaUrl()}"
    )
}

internal fun PlayerManager.currentSongNameForProgressLog(): String? = _currentSongFlow.value?.name

private fun PlayerManager.hasQueueStopMediaUrl(): Boolean = hasNonBlankMediaUrl(_currentMediaUrl.value)

internal fun hasNonBlankMediaUrl(url: String?): Boolean = !url.isNullOrBlank()

internal fun PlayerManager.stopPlaybackImmediatelyImpl(
    reason: String,
    forcePersist: Boolean = true
) {
    pauseImpl(
        forcePersist = forcePersist,
        commandSource = PlaybackCommandSource.LOCAL_SAFETY,
        allowFadeOut = false,
        debugReason = reason,
        flushPlayerOutput = true,
    )
}

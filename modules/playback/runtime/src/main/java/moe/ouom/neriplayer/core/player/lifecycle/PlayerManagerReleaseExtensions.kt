@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class]
)

package moe.ouom.neriplayer.core.player.lifecycle

import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker

import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity

import moe.ouom.neriplayer.data.settings.playback.toAudioEffectsSettings
import moe.ouom.neriplayer.data.settings.playback.toPlaybackSoundConfig
import moe.ouom.neriplayer.data.ltw.validation.format
import moe.ouom.neriplayer.core.player.audio.effects.AudioOutputRouteMonitor

import moe.ouom.neriplayer.core.player.engine.datasource.ServerMediaLoadErrorPolicy
import moe.ouom.neriplayer.core.player.lyrics.floating.FloatingLyricsContent
import moe.ouom.neriplayer.data.identity.stableKey
import android.app.Application
import coil.Coil
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache.CacheException
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.biliCookieRepo
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.currentPositionMsOr
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.audio.route.AudioDeviceRouteOwner
import moe.ouom.neriplayer.core.player.audio.route.PlayerManagerAudioDeviceRoutePort
import moe.ouom.neriplayer.core.player.debug.playWhenReadyChangeReasonName
import moe.ouom.neriplayer.core.player.debug.playbackSuppressionReasonName
import moe.ouom.neriplayer.core.player.debug.playbackStateName
import moe.ouom.neriplayer.core.player.audio.reactive.AudioReactive
import moe.ouom.neriplayer.core.player.audio.processing.PlaybackVolumeNormalizationState
import moe.ouom.neriplayer.core.player.engine.ReactiveRenderersFactory
import moe.ouom.neriplayer.core.player.engine.datasource.ConditionalHttpDataSourceFactory
import moe.ouom.neriplayer.core.player.lyrics.FloatingLyricsOverlayManager
import moe.ouom.neriplayer.core.player.lyrics.attachLiveLyricSurfaces
import moe.ouom.neriplayer.core.player.lyrics.clearExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.lyrics.releaseLiveLyricSurfaces
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.lyrics.syncExternalTranslatedLyrics
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.network.PlaybackNetworkResumeOwner
import moe.ouom.neriplayer.core.player.service.notification.FlymeStatusBarLyricSupport
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyRestoredStateSnapshot
import moe.ouom.neriplayer.core.player.persistence.applyCommittedLyricOverrides
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.core.player.persistence.restoreState
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.AppPlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.persistence.stats.AppPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.playback.playbackFailureRecoveryOwner
import moe.ouom.neriplayer.core.player.playback.clearAudioRouteMuteSuppression
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.playback.suppressPlaybackForAudioRouteLoss
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.shouldClearResumePlaybackRequestOnPlayWhenReadyPause
import moe.ouom.neriplayer.core.player.policy.command.shouldResumeSilentlyForListenTogetherNoisyPause
import moe.ouom.neriplayer.core.player.policy.offload.pcmAudioRequirements
import moe.ouom.neriplayer.core.player.policy.offload.shouldUpdateAudioOffloadForReactiveChange
import moe.ouom.neriplayer.core.player.policy.pending.shouldAcceptPlayerCallback
import moe.ouom.neriplayer.core.player.policy.pending.shouldExposePlayerCallbackState
import moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock
import moe.ouom.neriplayer.core.player.prefetch.prefetchNextGenericTrackUrl
import moe.ouom.neriplayer.core.player.resolver.youtube.YouTubeSeekRefreshPolicy
import moe.ouom.neriplayer.core.player.url.currentPlaybackCacheKeyForRecovery
import moe.ouom.neriplayer.core.player.url.shouldAdvanceAfterStuckTrackEnd
import moe.ouom.neriplayer.core.player.url.shouldAttemptUrlRefresh
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.url.shouldInvalidateCacheForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.shouldRecoverMissingLocalPlayback
import moe.ouom.neriplayer.core.player.url.shouldTreatPlaybackFailureAsTrackEnd
import moe.ouom.neriplayer.core.player.url.youtubePlaybackRecoveryStrategyForError
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbExclusiveLivenessPort
import moe.ouom.neriplayer.core.player.usb.recovery.PlayerManagerUsbInterruptedPlaybackPort
import moe.ouom.neriplayer.core.player.usb.recovery.UsbExclusiveLivenessOwner
import moe.ouom.neriplayer.core.player.usb.recovery.UsbInterruptedPlaybackOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSinkRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSinkRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.UsbRouteTransitionOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbSystemAudioRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbSystemAudioRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.PlayerManagerUsbPlaybackRoutePort
import moe.ouom.neriplayer.core.player.usb.route.UsbPlaybackRouteOwner
import moe.ouom.neriplayer.core.player.usb.route.AndroidUsbPlaybackNativeRoutePort
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemSoundGuard
import moe.ouom.neriplayer.core.player.watchdog.cancelPlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.clearActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackRuntimeWatchdog
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.core.player.watchdog.trySwitchToNextPlaybackCandidateForRecovery
import moe.ouom.neriplayer.data.settings.lyrics.LyricSourcePreferencePolicy
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.storage.CacheSizePolicy
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.usb.toUsbExclusivePreferences
import java.io.File

private const val MEDIA_CACHE_DIRECTORY_NAME = "media_cache"

internal fun PlayerManager.releaseImpl() {
    serverRecoveryPosition = null
    if (!initialized) {
        NPLogger.d("NERI-PlayerManager", "release(): ignored because manager is already released")
        return
    }
    logPlaybackReleaseBegin()
    statePersistenceCoordinator.close()
    statePersistenceWriter.invalidate()
    urlRefreshController.cancelCurrent()
    clearRestoredPlayback()
    try {
        preparePlaybackRelease()
        stopPlaybackForRelease()
        releaseUsbSessionsAndJobs()
        releaseMediaJobsAndLyrics()
        releasePlayerEngine()
        releasePlaybackScopes()
        clearPlaybackStateAfterRelease()
        NPLogger.d("NERI-PlayerManager", "release(): completed")
    } finally {
        finishPlaybackRelease()
    }
}

private fun PlayerManager.logPlaybackReleaseBegin() {
    NPLogger.d(
        "NERI-PlayerManager",
        "release(): begin, currentSong=${_currentSongFlow.value?.name}, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, isPlaying=${_isPlayingFlow.value}, mediaUrl=${_currentMediaUrl.value}, stack=[${debugStackHint()}]"
    )
}

private fun cancelJobForRelease(job: Job?) {
    job?.cancel()
}

private fun PlayerManager.preparePlaybackRelease() {
    updateResumePlaybackRequested(false)
    playbackTransportOwner.resetForRelease()
    cancelPlaybackStartupWatchdog(reason = "release")
    resetPlaybackRuntimeWatchdog(reason = "release")
    PlaybackNetworkResumeOwner.disarm("release")
    clearActivePlaybackCandidates()
    StartupAudioFocusController.release("player_release")

    audioDeviceRouteOwner.release()
}

private fun PlayerManager.stopPlaybackForRelease() {
    stopProgressUpdates()
    cancelVolumeFade(resetToFull = true)
    clearAudioRouteMuteSuppression(
        reason = "release",
        preserveExplicitRestore = false
    )
    cancelPendingPauseRequest(resetVolumeToFull = true)
    audioDeviceRouteOwner.cancelBluetoothDisconnectPause()
    flushPlaybackStatsAsync("release", stopTracking = true)
}

private fun PlayerManager.releaseUsbSessionsAndJobs() {
    playbackSoundOwner.cancelPersistenceForRelease()
    usbPlaybackRouteOwner.release()
}

private fun PlayerManager.releaseMediaJobsAndLyrics(): Unit = synchronized(this) {
    cancelJobForRelease(playJob)
    playJob = null
    cancelJobForRelease(currentGenericUrlPrefetchJob)
    currentGenericUrlPrefetchJob = null
    currentGenericUrlPrefetchKey = null
    genericUrlPrefetchCache.clear()
    cancelLyriconUpdate()
    cancelJobForRelease(externalBluetoothLyricsLoadJob)
    externalBluetoothLyricsLoadJob = null
    cancelJobForRelease(externalBluetoothTranslationLoadJob)
    externalBluetoothTranslationLoadJob = null
    externalBluetoothLyrics = emptyList()
    externalBluetoothPreferredLyricSource = null
    floatingTranslatedLyrics = emptyList()
    floatingTranslationMatchesByIndex = emptyMap()
    externalBluetoothLyricsSongKey = null
    externalBluetoothLyricsEnabled = false
    externalBluetoothTranslationEnabled = false
    dynamicIslandLyricsEnabled = false
    floatingLyricsEnabled = false
    floatingLyricsShowTranslation = true
    statusBarLyricsEnable = false
    clearExternalBluetoothLyricLine()
    releaseLiveLyricSurfaces()
    FloatingLyricsOverlayManager.release()
    LyriconManager.release()
}

internal fun PlayerManager.stopAudioOutputRouteMonitor() {
    audioOutputRouteMonitor?.stop()
    audioOutputRouteMonitor = null
}

private fun PlayerManager.releasePlayerEngine() {
    releasePlayerIfInitialized()
    playbackSoundOwner.releaseEngine()
    stopAudioOutputRouteMonitor()
    _playWhenReadyFlow.value = false
    _playerPlaybackStateFlow.value = Player.STATE_IDLE
    releaseMediaCache()
    closeConditionalHttpFactory()
    conditionalHttpFactory = null
}

private fun PlayerManager.releasePlayerIfInitialized() {
    if (isPlayerInitialized()) {
        runCatching { player.stop() }
        player.release()
    }
}

private fun PlayerManager.closeConditionalHttpFactory() {
    conditionalHttpFactory?.close()
}

private fun PlayerManager.releasePlaybackScopes() {
    mainScope.cancel()
    playbackStatsOwner.cancelSharedScopeAfterWrites()
}

private fun PlayerManager.clearPlaybackStateAfterRelease() {
    _isPlayingFlow.value = false
    _currentMediaUrl.value = null
    _currentPlaybackAudioInfo.value = null
    currentMediaUrlResolvedAtMs = 0L
    setCurrentSongForPlayback(null)
    publishCurrentQueue(emptyList(), -1)
    clearPendingSeekPosition()
    _playbackPositionMs.value = 0L

    consecutivePlayFailures = 0
}

private fun PlayerManager.finishPlaybackRelease() {
    initialized = false
    _localPlaylistsReadyFlow.value = false
    AudioReactive.setEnabledChangeListener(null)
    lastRequiresPcmAudioProcessing = null
    runCatching { closeConditionalHttpFactory() }
        .onFailure { NPLogger.w("NERI-PlayerManager", "release(): final conditional factory close failed", it) }
    conditionalHttpFactory = null
    UsbExclusiveSessionController.forceStopAllSessions("player_release_finally")
    UsbExclusiveSystemSoundGuard.forceRelease(application, "player_release_finally")
    StartupAudioFocusController.forceRelease("player_release_finally")
}

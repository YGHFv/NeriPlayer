@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.model.server.isServerSong

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.network.holdPlaybackForNetworkIfOffline
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.runtime.source.retrySongUrlResolution

import moe.ouom.neriplayer.core.player.runtime.source.SONG_URL_RESOLUTION_RETRY_COUNT

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockTarget
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.media.PlaybackMediaItemFactory
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution as LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.deriveCodecLabel
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.audio.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshDeferredCompletion
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshRequestHandle
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshRequestSemantics
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResultSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResultKind
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackStartPlan
import moe.ouom.neriplayer.core.player.runtime.refresh.resolveRefreshApplyAction
import moe.ouom.neriplayer.core.player.runtime.refresh.resolveRefreshedMediaStartPosition
import moe.ouom.neriplayer.core.player.runtime.refresh.shouldApplyRefreshResult
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.BiliSponsorBlockPlaybackController
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.playback.preparePlayerForManagedStart
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.prefetch.consumeGenericUrlPrefetch
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.core.player.quality.effectiveQqMusicQuality
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseAutoBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveQqMusicAutoBiliSource
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.qqmusic.library.QQMusicAudioStream
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.ltw.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.core.player.ltw.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.data.ltw.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.data.ltw.playback.shouldPreferListenTogetherSourceBeforeNeteaseFallback
import moe.ouom.neriplayer.data.ltw.playback.shouldSuppressListenTogetherResolverError
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_COUNT = 6
private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_DELAY_MS = 50L
private const val LOCAL_PLAYBACK_RESOLUTION_RETRY_MAX_DELAY_MS = 250L

internal fun shouldRetryLocalPlaybackResolution(
    song: SongItem,
    resolution: LocalPlaybackReferenceResolution,
    downloadSource: () -> PlayerDownloadAccess = { PlayerDependencies.downloads }
): Boolean {
    return when (resolution) {
        is LocalPlaybackReferenceResolution.TemporarilyUnavailable -> true
        LocalPlaybackReferenceResolution.Missing -> with(downloadSource()) {
            hasDownloadedSongCached(song) || hasRecentlyCompletedAudio(song) || isSongDownloadActive(song.stableKey())
        }
        LocalPlaybackReferenceResolution.NotIndexed,
        is LocalPlaybackReferenceResolution.Playable -> false
    }
}

internal fun PlayerManager.inspectExoPlayerCache(
    cacheKey: String
): CachedResourceIntegrity {
    val mediaCache = cache ?: return CachedResourceIntegrity(isComplete = false, requiresRepair = false, coveredLength = 0L)
    return try {
        val cachedSpans = mediaCache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) {
            return CachedResourceIntegrity(isComplete = false, requiresRepair = false, coveredLength = 0L)
        }

        val contentLength = ContentMetadata.getContentLength(
            mediaCache.getContentMetadata(cacheKey)
        )
        if (contentLength <= 0L) {
            NPLogger.d("NERI-PlayerManager", "缓存命中但缺少内容长度，视为未完成缓存: $cacheKey")
            return CachedResourceIntegrity(isComplete = false, requiresRepair = false, coveredLength = 0L)
        }

        val integrity = inspectCachedResourceSpans(cachedSpans, contentLength)
        when {
            integrity.requiresRepair -> NPLogger.w(
                "NERI-PlayerManager",
                "缓存 span 文件缺失或长度异常，标记为损坏: key=$cacheKey, " +
                    "covered=${integrity.coveredLength}/$contentLength"
            )

            integrity.isComplete -> NPLogger.d(
                "NERI-PlayerManager",
                "缓存完整可用: $cacheKey, length=$contentLength, spans=${cachedSpans.size}"
            )

            else -> NPLogger.d(
                "NERI-PlayerManager",
                "缓存未完整覆盖: $cacheKey, " +
                    "covered=${integrity.coveredLength}/$contentLength"
            )
        }
        integrity
    } catch (e: Exception) {
        NPLogger.w("NERI-PlayerManager", "检查缓存完整性失败: ${e.message}")
        CachedResourceIntegrity(isComplete = false, requiresRepair = true, coveredLength = 0L)
    }
}

internal fun PlayerManager.hasCompleteExoPlayerCache(cacheKey: String): Boolean {
    return inspectExoPlayerCache(cacheKey).isComplete
}

internal suspend fun PlayerManager.prepareExoPlayerCacheForPrefetch(
    cacheKey: String,
    shouldApplyMutation: () -> Boolean = { true }
): CachePrefetchReadiness {
    val currentCache = cache ?: return CachePrefetchReadiness.UNAVAILABLE

    if (loadPlaybackCacheKeySafety(cacheKey)) {
        if (cache !== currentCache) return CachePrefetchReadiness.UNAVAILABLE
        return if (
            invalidateCachedResourceForPlaybackRecovery(
                cacheKey = cacheKey,
                reason = "prefetch_unsafe_cache_key",
                shouldApplyMutation = shouldApplyMutation
            )
        ) {
            CachePrefetchReadiness.READY_FOR_PREFETCH
        } else {
            CachePrefetchReadiness.UNAVAILABLE
        }
    }

    val integrity = inspectExoPlayerCache(cacheKey)
    if (cache !== currentCache) return CachePrefetchReadiness.UNAVAILABLE
    if (integrity.isComplete) return CachePrefetchReadiness.COMPLETE
    if (!integrity.requiresRepair) return CachePrefetchReadiness.READY_FOR_PREFETCH

    return if (
        invalidateCachedResourceForPlaybackRecovery(
            cacheKey = cacheKey,
            reason = "prefetch_integrity_check",
            shouldApplyMutation = shouldApplyMutation
        )
    ) {
        CachePrefetchReadiness.READY_FOR_PREFETCH
    } else {
        CachePrefetchReadiness.UNAVAILABLE
    }
}

internal suspend fun PlayerManager.invalidateMismatchedCachedResource(
    cacheKey: String,
    expectedContentLength: Long?,
    shouldApplyMutation: () -> Boolean = { true }
) = withContext(Dispatchers.IO) {
    val expectedLength = expectedContentLength?.takeIf { it > 0L } ?: return@withContext
    val mediaCache = cache ?: return@withContext

    try {
        val cachedSpans = mediaCache.getCachedSpans(cacheKey)
        if (cachedSpans.isEmpty()) return@withContext

        val cachedContentLength = ContentMetadata.getContentLength(
            mediaCache.getContentMetadata(cacheKey)
        )
        if (!shouldReplaceCachedPreviewResource(cachedContentLength, expectedLength)) {
            return@withContext
        }

        NPLogger.w(
            "NERI-PlayerManager",
            "缓存疑似预览片段，移除旧缓存以便重新拉取完整资源: key=$cacheKey, cached=$cachedContentLength, expected=$expectedLength"
        )
        if (!shouldApplyMutation()) return@withContext
        mediaCache.removeResource(cacheKey)
    } catch (e: Exception) {
        NPLogger.w(
            "NERI-PlayerManager",
            "移除不匹配缓存失败: key=$cacheKey, error=${e.message}"
        )
    }
}

internal fun PlayerManager.currentPlaybackCacheKeyForRecovery(): String? {
    offlineCacheKeyFromUrl(_currentMediaUrl.value)?.let { return it }

    if (isPlayerInitialized()) {
        player.currentMediaItem?.localConfiguration?.customCacheKey
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    val song = _currentSongFlow.value ?: return null
    if (isLocalSong(song)) return null
    return currentPlaybackCandidate()?.cacheKeyOverride
        ?.takeIf { it.isNotBlank() }
        ?: computeCacheKey(song).takeIf { it.isNotBlank() }
}

internal suspend fun PlayerManager.invalidateCachedResourceForPlaybackRecovery(
    cacheKey: String,
    reason: String,
    shouldApplyMutation: () -> Boolean = { true }
): Boolean = withContext(Dispatchers.IO) {
    if (cacheKey.isBlank()) return@withContext false
    val mediaCache = cache ?: return@withContext false
    if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
    try {
        if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
        mediaCache.removeResource(cacheKey)
        if (cache !== mediaCache) return@withContext false
        if (mediaCache.getCachedSpans(cacheKey).isNotEmpty()) {
            markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
            NPLogger.w(
                "NERI-PlayerManager",
                "异常播放缓存未完全移除: key=$cacheKey, reason=$reason"
            )
            return@withContext false
        }
        if (cache !== mediaCache || !shouldApplyMutation()) return@withContext false
        if (!clearPlaybackCacheKeyUnsafe(mediaCache, cacheKey)) {
            NPLogger.w(
                "NERI-PlayerManager",
                "异常播放缓存标记未清除，跳过缓存复用: key=$cacheKey, reason=$reason"
            )
            return@withContext false
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "已移除异常播放缓存: key=$cacheKey, reason=$reason"
        )
        true
    } catch (e: Exception) {
        if (cache === mediaCache) {
            markPlaybackCacheKeyUnsafe(mediaCache, cacheKey)
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "移除异常播放缓存失败: key=$cacheKey, reason=$reason, error=${e.message}"
        )
        false
    }
}

internal suspend fun PlayerManager.invalidateCachedResourceBeforeResolve(
    cacheKey: String,
    reason: String,
    shouldApplyMutation: () -> Boolean
) = invalidateCachedResourceForPlaybackRecovery(cacheKey, reason, shouldApplyMutation)

internal suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowLocalFallback: Boolean = true,
    allowPreviewFallback: Boolean = true,
    allowAutoBiliFallback: Boolean = true
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val effectiveQuality = effectiveNeteaseQuality()
        val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
        var previewFallback: SongUrlResult.Success? = null
        var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null
        var requiresLogin = false

        for ((index, quality) in qualityCandidates.withIndex()) {
            val resp = neteaseClient.getSongDownloadUrl(
                song.id,
                level = quality
            )
            NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    requiresLogin = true
                    if (shouldRetryNeteaseWithLowerQualityAfterLogin(
                            qualityIndex = index,
                            lastQualityIndex = qualityCandidates.lastIndex
                        )
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质需要登录，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                    break
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    val success = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = quality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    ).let { result ->
                        if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            result.copy(
                                cacheKeyOverride = buildNeteasePreviewCacheKey(
                                    songId = song.id,
                                    preferredQuality = quality
                                )
                            )
                        } else {
                            result
                        }
                    }
                    if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        if (quality != effectiveQuality) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可用，已自动降级: id=${song.id}, preferred=$effectiveQuality, resolved=$quality"
                            )
                        }
                        return@withContext success
                    }

                    previewFallback = success
                    if (index < qualityCandidates.lastIndex) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                    lastFailureReason = parsed.reason
                    if (index < qualityCandidates.lastIndex &&
                        shouldRetryNeteaseWithLowerQuality(parsed.reason)
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                        )
                        continue
                    }
                    break
                }
            }
        }

        if (previewFallback != null ||
            lastFailureReason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION
        ) {
            if (allowLocalFallback) {
                tryResolveNeteaseMatchedLocalSource(song)?.let {
                    return@withContext it
                }
            }
            if (allowAutoBiliFallback) {
                tryResolveNeteaseAutoBiliSource(song, sideEffects)?.let {
                    return@withContext it
                }
            }
        }

        if (allowPreviewFallback) {
            previewFallback?.let { return@withContext it }
        }

        if (requiresLogin) {
            return@withContext SongUrlResult.RequiresLogin
        }

        if (!suppressError) {
            val messageRes = when (lastFailureReason) {
                NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                    CoreCommonR.string.player_netease_no_permission_switch_platform
                NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                null -> CoreCommonR.string.error_no_play_url
            }
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
        }
        SongUrlResult.Failure
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

internal suspend fun PlayerManager.getQQMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    val songMid = song.audioId?.trim()?.takeIf(String::isNotBlank)
    if (songMid == null) {
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url))
                )
            }
        }
        return@withContext SongUrlResult.Failure
    }
    try {
        val preferredQuality = effectiveQqMusicQuality()
        val stream = qqMusicClient.getPlaybackStream(
            songMid = songMid,
            mediaMid = song.subAudioId,
            preferredQuality = preferredQuality
        )
        if (stream.qualityKey != preferredQuality) {
            NPLogger.w(
                "NERI-PlayerManager",
                "QQ Music stream quality fallback: mid=$songMid, preferred=$preferredQuality, resolved=${stream.qualityKey}"
            )
        }
        val result = SongUrlResult.Success(
            url = stream.url,
            candidateUrls = stream.candidateUrls,
            durationMs = song.durationMs.takeIf { it > 0L },
            mimeType = stream.mimeType,
            noticeMessage = if (stream.isPreviewClip) {
                getLocalizedString(CoreCommonR.string.player_qq_music_preview_only)
            } else {
                null
            },
            expectedContentLength = stream.contentLength,
            audioInfo = buildQqMusicPlaybackAudioInfo(stream) { getLocalizedString(it) },
            representationIdentity = "qqmusic-$songMid-${stream.qualityKey}",
            isPreviewClip = stream.isPreviewClip
        )
        if (stream.isPreviewClip && qqMusicAutoSourceSwitchEnabled) {
            tryResolveQqMusicAutoBiliSource(song, sideEffects)?.let {
                return@withContext it
            }
        }
        return@withContext result
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NPLogger.e("NERI-PlayerManager", "Failed to get QQ Music play url: mid=$songMid", error)
        if (qqMusicAutoSourceSwitchEnabled) {
            tryResolveQqMusicAutoBiliSource(song, sideEffects)?.let {
                return@withContext it
            }
        }
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            error.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

private fun buildQqMusicPlaybackAudioInfo(
    stream: QQMusicAudioStream,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    val qualityKey = stream.qualityKey.trim().lowercase().ifBlank { "exhigh" }
    return PlaybackAudioInfo(
        source = PlaybackAudioSource.QQ_MUSIC,
        qualityKey = qualityKey,
        qualityLabel = qualityLabelForQqMusic(qualityKey, getLocalizedString),
        qualityOptions = buildQqMusicQualityOptions(getLocalizedString),
        codecLabel = deriveCodecLabel(stream.mimeType),
        mimeType = stream.mimeType
    )
}

internal suspend fun PlayerManager.getBiliAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    playbackRequestTokenOverride: Long? = null
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val resolved = resolveBiliSong(song, biliClient)
        if (resolved == null || resolved.cid == 0L) {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(CoreCommonR.string.player_playback_video_info_unavailable)
                        )
                    )
                }
            }
            return@withContext SongUrlResult.Failure
        }

        if (!isListenTogetherActive() && _currentSongFlow.value?.sameIdentityAs(song) == true) {
            val requestToken = playbackRequestTokenOverride ?: playbackRequestToken
            BiliSponsorBlockPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliSponsorBlockTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid,
                    durationMs = resolved.pageInfo
                        ?.durationSec
                        ?.toLong()
                        ?.times(1_000L)
                        ?.takeIf { it > 0L }
                        ?: song.durationMs
                ),
                requestToken = requestToken,
                scope = ioScope
            )
            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliVideoSkipTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid
                ),
                requestToken = requestToken
            )
        }

        val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
            resolved.videoInfo.bvid,
            resolved.cid,
            preferredKeyOverride = effectiveBiliQuality()
        )

        if (audioStream?.url != null) {
            NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
            SongUrlResult.Success(
                url = audioStream.url,
                candidateUrls = audioStream.candidateUrls,
                mimeType = audioStream.mimeType,
                expectedContentLength = null,
                audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams) {
                    getLocalizedString(it)
                },
                representationIdentity = buildBiliRepresentationIdentity(audioStream)
            )
        } else {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

internal suspend fun PlayerManager.getYouTubeMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    if (videoId.isNullOrBlank()) {
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
            }
        }
        return@withContext SongUrlResult.Failure
    }

    val resolveStartedAtMs = System.currentTimeMillis()
    try {
        val preferredQuality = youtubeRecoveryStrategy?.preferredQualityOverride
            ?: effectiveYouTubeQuality()
        val requireDirect = youtubeRecoveryStrategy?.requireDirect ?: false
        // 首播保留用户选择; 出错恢复时才切到更稳的 m4a 直链
        val preferM4a = youtubeRecoveryStrategy?.preferM4a ?: false
        val resolvedPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = videoId,
            preferredQualityOverride = preferredQuality,
            forceRefresh = forceRefresh,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            shareInFlight = youtubeRecoveryStrategy == null,
            allowUnverifiedDirectFallback =
                youtubeRecoveryStrategy?.allowUnverifiedDirectFallback ?: true
        )?.takeIf { it.url.isNotBlank() }
        if (resolvedPlayableAudio != null) {
            sideEffects.updateDuration {
                maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
            }
            NPLogger.d(
                "NERI-PlayerManager",
                "Resolved YouTube Music stream: videoId=$videoId, quality=$preferredQuality, recovery=${youtubeRecoveryStrategy != null}, preferM4a=$preferM4a, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            SongUrlResult.Success(
                url = resolvedPlayableAudio.url,
                durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                mimeType = resolvedPlayableAudio.mimeType,
                expectedContentLength = resolvedPlayableAudio.contentLength,
                audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio) {
                    getLocalizedString(it)
                },
                representationIdentity = buildYouTubeRepresentationIdentity(resolvedPlayableAudio),
                cacheKeyOverride = youtubeRecoveryStrategy?.let { strategy ->
                    computeYouTubeCacheKey(
                        videoId = videoId,
                        preferredQuality = strategy.preferredQualityOverride,
                        preferM4a = strategy.preferM4a
                    )
                }
            )
        } else {
            NPLogger.w(
                "NERI-PlayerManager",
                "Resolve YouTube Music stream returned empty: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e(
            "NERI-PlayerManager",
            "Failed to get YouTube Music play url: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}",
            e
        )
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

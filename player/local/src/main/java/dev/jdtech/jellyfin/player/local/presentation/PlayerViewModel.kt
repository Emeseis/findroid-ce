package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.models.ItemPreferenceDto
import dev.jdtech.jellyfin.models.MediaSegmentAction
import dev.jdtech.jellyfin.player.core.domain.models.PlayerChapter
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.PreferenceTrack
import dev.jdtech.jellyfin.models.languageTagsMatch
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.player.local.domain.PlayerTrackDescriptor
import dev.jdtech.jellyfin.player.local.domain.StillWatchingTracker
import dev.jdtech.jellyfin.player.local.domain.TrackPreferenceMatcher
import dev.jdtech.jellyfin.player.local.domain.TrickplayLoader
import dev.jdtech.jellyfin.player.local.mpv.MPVPlayer
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.Constants
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

@HiltViewModel
class PlayerViewModel
@Inject
constructor(
    private val application: Application,
    private val playlistManager: PlaylistManager,
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(), Player.Listener {
    val player: Player

    private val _uiState =
        MutableStateFlow(
            UiState(
                currentItemId = null,
                currentItemTitle = "",
                currentSegment = null,
                currentSkipButtonStringRes = R.string.player_controls_skip_intro,
                currentTrickplay = null,
                currentChapters = emptyList(),
                fileLoaded = false,
                showStillWatching = false,
                stillWatchingTimeoutSeconds = 30,
            )
        )
    val uiState = _uiState.asStateFlow()

    // Buffered so transient events (e.g. NavigateBack on STATE_ENDED, error toasts) are not
    // dropped if the collector is paused mid-config-change. trySend on a rendezvous channel
    // silently fails when nobody is collecting.
    private val eventsChannel = Channel<PlayerEvents>(Channel.BUFFERED)
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    data class UiState(
        /** The item currently loaded in the player. `null` until the first transition fires. */
        val currentItemId: UUID?,
        val currentItemTitle: String,
        val currentSegment: FindroidSegment?,
        val currentSkipButtonStringRes: Int,
        val currentTrickplay: Trickplay?,
        val currentChapters: List<PlayerChapter>,
        val fileLoaded: Boolean,
        val showStillWatching: Boolean,
        val stillWatchingTimeoutSeconds: Int,
    )

    private var items: MutableList<PlayerItem> = mutableListOf()
    private var lastAppliedPreferenceMediaId: String? = null
    private var lastPreferenceWriteJob: Job? = null

    private val trackSelector = DefaultTrackSelector(application)
    var playWhenReady = true
    private var currentMediaItemIndex = savedStateHandle["mediaItemIndex"] ?: 0
    private var playbackPosition: Long = savedStateHandle["position"] ?: 0
    private var currentMediaItemSegments: List<FindroidSegment> = emptyList()

    // Segments preferences
    var segmentsSkipButton: Boolean = false
    private var segmentsSkipButtonTypes: Set<String> = emptySet()
    var segmentsSkipButtonDuration: Long = 0L
    var segmentsAutoSkip: Boolean = false
    private var segmentsAutoSkipTypes: Set<String> = emptySet()
    private var segmentsAutoSkipMode: String = "always"

    // Per-type tri-state action (SKIP / ASK / IGNORE) — issue #12. This is the
    // primary input to updateCurrentSegment; the legacy global toggles above
    // are still honoured as a fallback so users who only customised those keep
    // their existing behaviour after upgrading.
    //
    // Values are nullable: a null entry means "the user has not configured a
    // per-type action for this segment type", which lets resolveSegmentAction
    // fall through to the legacy toggles. Storing a non-null default here
    // would always win against legacy and silently reset existing users'
    // playback behaviour on upgrade.
    private var segmentsActions: Map<FindroidSegmentType, MediaSegmentAction?> = emptyMap()

    var playbackSpeed: Float = 1f

    var isInPictureInPictureMode: Boolean = false

    // "Are you still watching?" — prompt fires after N consecutive auto-advanced episodes
    // OR M minutes without a user touch. Either threshold can be disabled by setting it
    // to 0 in settings.
    private val stillWatchingTracker: StillWatchingTracker
    private val stillWatchingPromptTimeoutSeconds: Int
    private var stillWatchingTimeoutJob: Job? = null

    // Armed immediately before an app-initiated seekToNextMediaItem() (auto-advance). Because
    // pauseAtEndOfMediaItems is on, the app performs that transition as an explicit seek, which
    // surfaces in onPositionDiscontinuity as DISCONTINUITY_REASON_SEEK — indistinguishable from a
    // user seek without this hint. onPositionDiscontinuity checks and clears the flag so it does
    // not treat our own advance as a user interaction (which would reset the still-watching
    // counter and refresh the inactivity timer). The seek→discontinuity callback is delivered
    // asynchronously on the main looper for both ExoPlayer and MPVPlayer, so the flag must persist
    // until the callback fires rather than being cleared right after the seek call.
    private var pendingAutoAdvanceSeek = false

    init {
        val episodes = appPreferences.getValue(appPreferences.stillWatchingAfterEpisodes)
        val minutes = appPreferences.getValue(appPreferences.stillWatchingAfterMinutes)
        stillWatchingTracker =
            StillWatchingTracker(
                autoAdvanceThreshold = episodes.coerceAtLeast(0),
                inactivityThresholdMs =
                    if (minutes <= 0) StillWatchingTracker.OFF_MS else minutes * 60_000L,
            )
        stillWatchingTracker.reset(nowMs = System.currentTimeMillis())
        stillWatchingPromptTimeoutSeconds =
            appPreferences
                .getValue(appPreferences.stillWatchingPromptTimeoutSeconds)
                .coerceAtLeast(5)
        _uiState.update { it.copy(stillWatchingTimeoutSeconds = stillWatchingPromptTimeoutSeconds) }

        segmentsSkipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        segmentsSkipButtonTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonType)
        segmentsSkipButtonDuration =
            appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration)
        segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        segmentsAutoSkipTypes =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipType)
        segmentsAutoSkipMode =
            appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkipMode)

        // Resolve the per-type tri-state preference. fromPreferenceValueOrNull
        // returns null when the value is absent or corrupted, so the legacy
        // global toggles remain reachable in resolveSegmentAction.
        segmentsActions = mapOf(
            FindroidSegmentType.INTRO to MediaSegmentAction.fromPreferenceValueOrNull(
                appPreferences.getValue(appPreferences.playerMediaSegmentsIntroAction),
            ),
            FindroidSegmentType.OUTRO to MediaSegmentAction.fromPreferenceValueOrNull(
                appPreferences.getValue(appPreferences.playerMediaSegmentsOutroAction),
            ),
            FindroidSegmentType.RECAP to MediaSegmentAction.fromPreferenceValueOrNull(
                appPreferences.getValue(appPreferences.playerMediaSegmentsRecapAction),
            ),
            FindroidSegmentType.PREVIEW to MediaSegmentAction.fromPreferenceValueOrNull(
                appPreferences.getValue(appPreferences.playerMediaSegmentsPreviewAction),
            ),
            FindroidSegmentType.COMMERCIAL to MediaSegmentAction.fromPreferenceValueOrNull(
                appPreferences.getValue(appPreferences.playerMediaSegmentsCommercialAction),
            ),
        )

        val audioAttributes =
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTunnelingEnabled(true)
                .setPreferredAudioLanguage(
                    appPreferences.getValue(appPreferences.preferredAudioLanguage)
                )
                .setPreferredTextLanguage(
                    appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
                )
        )


        val playerBackend = appPreferences.getValue(appPreferences.playerBackend)
        player = when (playerBackend) {
            "exoplayer" -> {
                val renderersFactory =
                    DefaultRenderersFactory(application)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                ExoPlayer.Builder(application, renderersFactory)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelector(trackSelector)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .build()
            }
            "mpv" -> {
                MPVPlayer.Builder(application)
                    .setAudioAttributes(audioAttributes, true)
                    .setTrackSelectionParameters(trackSelector.parameters)
                    .setSeekBackIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekBackInc)
                    )
                    .setSeekForwardIncrementMs(
                        appPreferences.getValue(appPreferences.playerSeekForwardInc)
                    )
                    .setPauseAtEndOfMediaItems(true)
                    .setVideoOutput(appPreferences.getValue(appPreferences.playerMpvVo))
                    .setAudioOutput(appPreferences.getValue(appPreferences.playerMpvAo))
                    .setHwDec(appPreferences.getValue(appPreferences.playerMpvHwdec))
                    .build()
            }

            else -> throw RuntimeException("$playerBackend is not a valid player backend")
        }
    }

    fun initializePlayer(itemId: UUID, itemKind: String, startFromBeginning: Boolean) {
        player.addListener(this)

        viewModelScope.launch {
            val startItem =
                try {
                    playlistManager.getInitialItem(
                        itemId = itemId,
                        itemKind = BaseItemKind.fromName(itemKind),
                        mediaSourceIndex = null,
                        startFromBeginning = startFromBeginning,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(application, e.localizedMessage, Toast.LENGTH_LONG).show()
                    null
                }

            if (startItem == null) {
                Timber.e("No start item, stopping player initialization")
                return@launch
            }

            items = listOfNotNull(startItem).toMutableList()
            currentMediaItemIndex = items.indexOf(startItem)

            val mediaItems = mutableListOf<MediaItem>()
            try {
                for (item in items) {
                    mediaItems.add(item.toMediaItem())
                }
            } catch (e: Exception) {
                Timber.e(e)
            }

            val startPosition =
                if (playbackPosition == 0L) {
                    items.getOrNull(currentMediaItemIndex)?.playbackPosition ?: C.TIME_UNSET
                } else {
                    playbackPosition
                }

            startItem.groupingId?.let { groupingId ->
                repository.getItemPreference(groupingId)?.let { preference ->
                    val parametersBuilder = player.trackSelectionParameters.buildUpon()
                    preference.audioLanguage?.let { parametersBuilder.setPreferredAudioLanguage(it) }
                    if (preference.subtitleLanguage == "none") {
                        parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    } else {
                        preference.subtitleLanguage?.let {
                            parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            parametersBuilder.setPreferredTextLanguage(it)
                        }
                    }
                    player.trackSelectionParameters = parametersBuilder.build()
                }
            }

            player.setMediaItems(mediaItems, 0, startPosition)
            player.prepare()
            player.play()
        }
    }

    private fun PlayerItem.toMediaItem(): MediaItem {
        val streamUrl = mediaSourceUri
        val mediaSubtitles =
            externalSubtitles.map { externalSubtitle ->
                var selectionFlags = 0
                if (externalSubtitle.isForced) {
                    selectionFlags = selectionFlags or C.SELECTION_FLAG_FORCED
                }
                if (externalSubtitle.isDefault) {
                    selectionFlags = selectionFlags or C.SELECTION_FLAG_DEFAULT
                }
                MediaItem.SubtitleConfiguration.Builder(externalSubtitle.uri)
                    .setLabel(
                        externalSubtitle.title.ifBlank { application.getString(R.string.external) }
                    )
                    .setMimeType(externalSubtitle.mimeType)
                    .setLanguage(externalSubtitle.language)
                    .setSelectionFlags(selectionFlags)
                    .setRoleFlags(C.ROLE_FLAG_AUXILIARY)
                    .build()
            }

        Timber.d("Stream url: $streamUrl")
        val mediaItem =
            MediaItem.Builder()
                .setMediaId(itemId.toString())
                .setUri(streamUrl)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                .setSubtitleConfigurations(mediaSubtitles)
                .apply {
                    // A transcode is delivered as an HLS manifest. Tell ExoPlayer
                    // explicitly so it does not have to infer the type from the URL.
                    if (isTranscoded) setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                .build()

        return mediaItem
    }

    private fun releasePlayer() {
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        val duration = player.duration

        _uiState.value.currentTrickplay?.loader?.release()
        _uiState.update { it.copy(currentTrickplay = null) }
        playWhenReady = false
        playbackPosition = 0L
        currentMediaItemIndex = 0
        player.removeListener(this)
        player.release()

        if (mediaId != null && duration != C.TIME_UNSET && duration > 0) {
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    Timber.d("Sending playback stop")
                    repository.postPlaybackStop(
                        UUID.fromString(mediaId),
                        position.times(10000),
                        position.div(duration.toFloat()).times(100).toInt(),
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    fun updatePlaybackProgress() {
        Timber.d("Updating playback progress")
        viewModelScope.launch(Dispatchers.Main) {
            savedStateHandle["position"] = player.currentPosition
            val mediaItem = player.currentMediaItem ?: return@launch
            if (mediaItem.mediaId.isNotEmpty()) {
                val itemId = UUID.fromString(mediaItem.mediaId)
                try {
                    repository.postPlaybackProgress(
                        itemId,
                        player.currentPosition.times(10000),
                        !player.isPlaying,
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }

    /**
     * Inspect the player's current position and update the UI to reflect the
     * configured action for any segment it has entered. Suspends rather than
     * launching its own coroutine so the activity's per-second poll loop can
     * call it directly without paying for an extra `viewModelScope.launch`
     * each tick (PR #20 review).
     */
    suspend fun updateCurrentSegment() = withContext(Dispatchers.Main) {
        Timber.d("Updating current segment")
        if (currentMediaItemSegments.isEmpty()) {
            return@withContext
        }

        val milliSeconds = player.currentPosition

        // Get current segment, - 100 milliseconds to avoid showing button after segment ends
        val currentSegment =
            currentMediaItemSegments.find { segment ->
                milliSeconds in segment.startTicks..<(segment.endTicks - 100L)
            }

        if (currentSegment == null) {
            // Remove button if not pressed and there is no current segment
            if (_uiState.value.currentSegment != null) {
                _uiState.update { it.copy(currentSegment = null) }
            }
            return@withContext
        }

        Timber.tag("SegmentInfo").d("currentSegment: %s", currentSegment)

        when (resolveSegmentAction(currentSegment)) {
            MediaSegmentAction.SKIP -> {
                // Auto Skip segment. The legacy `auto skip mode` (always vs
                // PIP-only) is still respected — if the user picked PIP-only
                // and we are not in PiP, fall back to showing the ASK
                // button instead of nothing, since they clearly want to
                // skip the segment in some form.
                val pipOnly =
                    segmentsAutoSkipMode == Constants.PlayerMediaSegmentsAutoSkip.PIP
                if (!pipOnly || isInPictureInPictureMode) {
                    skipSegment(currentSegment)
                } else {
                    _uiState.update {
                        it.copy(
                            currentSegment = currentSegment,
                            currentSkipButtonStringRes =
                                getSkipButtonTextStringId(currentSegment),
                        )
                    }
                }
            }
            MediaSegmentAction.ASK -> {
                // Show the skip button; the UI handles its own auto-hide
                // after `segmentsSkipButtonDuration` seconds.
                _uiState.update {
                    it.copy(
                        currentSegment = currentSegment,
                        currentSkipButtonStringRes = getSkipButtonTextStringId(currentSegment),
                    )
                }
            }
            MediaSegmentAction.IGNORE -> {
                if (_uiState.value.currentSegment != null) {
                    _uiState.update { it.copy(currentSegment = null) }
                }
            }
        }
    }

    /**
     * Resolve the configured [MediaSegmentAction] for the segment that the
     * player is currently inside.
     *
     * Priority:
     *  1. The per-type preference (issue #12).
     *  2. Legacy global toggles — autoSkip+type set, then skipButton+type set —
     *     for users who never touched the new per-type controls.
     *  3. [MediaSegmentAction.IGNORE] as the safe default.
     *
     * The actual logic lives on [MediaSegmentAction.Companion.resolve] so it
     * can be unit-tested without the PlayerViewModel's Android dependencies.
     */
    private fun resolveSegmentAction(segment: FindroidSegment): MediaSegmentAction =
        MediaSegmentAction.resolve(
            segmentType = segment.type,
            perTypeActions = segmentsActions,
            legacyAutoSkipEnabled = segmentsAutoSkip,
            legacyAutoSkipTypes = segmentsAutoSkipTypes,
            legacySkipButtonEnabled = segmentsSkipButton,
            legacySkipButtonTypes = segmentsSkipButtonTypes,
        )

    /**
     * True if at least one segment type is set to SKIP or ASK — used by the
     * activity to decide whether to start the per-second segment poller.
     *
     * `segmentsActions` entries may be null (the user has not picked a per-type
     * action) so we only short-circuit on non-null non-IGNORE values; null
     * entries fall back to the legacy toggles, which we test separately.
     */
    fun shouldPollSegments(): Boolean {
        if (segmentsAutoSkip || segmentsSkipButton) return true
        return segmentsActions.values.any { it != null && it != MediaSegmentAction.IGNORE }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("Playing MediaItem: ${mediaItem?.mediaId}")
        lastAppliedPreferenceMediaId = null
        savedStateHandle["mediaItemIndex"] = player.currentMediaItemIndex
        viewModelScope.launch {
            try {
                items
                    .first { it.itemId.toString() == player.currentMediaItem?.mediaId }
                    .let { item ->
                        val itemTitle =
                            if (item.parentIndexNumber != null && item.indexNumber != null) {
                                if (item.indexNumberEnd == null) {
                                    "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
                                } else {
                                    "S${item.parentIndexNumber}:E${item.indexNumber}-${item.indexNumberEnd} - ${item.name}"
                                }
                            } else {
                                item.name
                            }
                        _uiState.update {
                            it.copy(
                                currentItemId = item.itemId,
                                currentItemTitle = itemTitle,
                                currentSegment = null,
                                currentChapters = item.chapters,
                                fileLoaded = false,
                            )
                        }

                        repository.postPlaybackStart(item.itemId)

                        if (shouldPollSegments()) {
                            getSegments(item.itemId)
                        }

                        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
                            getTrickplay(item)
                        }

                        playlistManager.setCurrentMediaItemIndex(item.itemId)

                        val previousItem = playlistManager.getPreviousPlayerItem()
                        if (previousItem != null) {
                            items.add(player.currentMediaItemIndex, previousItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex,
                                previousItem.toMediaItem(),
                            )
                        }

                        val nextItem = playlistManager.getNextPlayerItem()
                        if (nextItem != null) {
                            items.add(player.currentMediaItemIndex + 1, nextItem)
                            player.addMediaItem(
                                player.currentMediaItemIndex + 1,
                                nextItem.toMediaItem(),
                            )
                        }

                        Timber.tag("PlayerItems").d(items.map { it.indexNumber }.toString())
                    }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // Report playback stopped for current item and transition to the next one
        if (
            !playWhenReady &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                player.playbackState == ExoPlayer.STATE_READY
        ) {
            viewModelScope.launch {
                val mediaId = player.currentMediaItem?.mediaId
                val position = player.currentPosition
                val duration = player.duration
                if (mediaId != null && duration != C.TIME_UNSET && duration > 0) {
                    try {
                        repository.postPlaybackStop(
                            UUID.fromString(mediaId),
                            position.times(10000),
                            position.div(duration.toFloat()).times(100).toInt(),
                        )
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                }

                // "Are you still watching?" — only intercept if there's another item to advance
                // to. If this was the last item, fall through to the normal seek+play (which
                // becomes a no-op for seek and the player will hit STATE_ENDED → NavigateBack).
                if (
                    player.hasNextMediaItem() &&
                        stillWatchingTracker.onAutoAdvance(nowMs = System.currentTimeMillis())
                ) {
                    showStillWatchingPrompt()
                    return@launch
                }
                autoAdvanceToNextItem()
            }
        }
    }

    /**
     * Advance to the next item as part of automatic playback (end-of-item auto-advance or a
     * confirmed still-watching prompt). Arms [pendingAutoAdvanceSeek] so the resulting
     * DISCONTINUITY_REASON_SEEK is not mistaken for a user interaction. The flag is only set when
     * a next item actually exists — otherwise seekToNextMediaItem() is a no-op that fires no
     * discontinuity, and the flag would leak onto the next genuine user seek.
     */
    private fun autoAdvanceToNextItem() {
        if (player.hasNextMediaItem()) {
            pendingAutoAdvanceSeek = true
        }
        player.seekToNextMediaItem()
        player.play()
    }

    private fun showStillWatchingPrompt() {
        Timber.d("Still-watching threshold tripped — prompting user")
        _uiState.update { it.copy(showStillWatching = true) }
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob =
            viewModelScope.launch {
                delay(stillWatchingPromptTimeoutSeconds * 1000L)
                // Timeout: user is gone. Hide the dialog and leave the player paused. The current
                // item's progress was already reported above (postPlaybackStop with the actual
                // position), so the season won't be marked watched.
                _uiState.update { it.copy(showStillWatching = false) }
                Timber.d("Still-watching prompt timed out — staying paused")
            }
    }

    /** Called when the user confirms the still-watching prompt. Resumes auto-advance. */
    fun acknowledgeStillWatching() {
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        stillWatchingTracker.onUserInteraction(nowMs = System.currentTimeMillis())
        _uiState.update { it.copy(showStillWatching = false) }
        if (player.hasNextMediaItem()) {
            autoAdvanceToNextItem()
        }
    }

    /** Called when the user dismisses the still-watching prompt without confirming. */
    fun dismissStillWatching() {
        stillWatchingTimeoutJob?.cancel()
        stillWatchingTimeoutJob = null
        _uiState.update { it.copy(showStillWatching = false) }
    }

    /**
     * Reset the still-watching counters. Call from every user-driven action — touches,
     * gestures, button presses, dialog choices.
     */
    fun markUserInteraction() {
        stillWatchingTracker.onUserInteraction(nowMs = System.currentTimeMillis())
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Player error: ${error.errorCodeName}")
        eventsChannel.trySend(PlayerEvents.PlayerError(error))
    }

    override fun onPlaybackStateChanged(state: Int) {
        var stateString = "UNKNOWN_STATE             -"
        when (state) {
            ExoPlayer.STATE_IDLE -> {
                stateString = "ExoPlayer.STATE_IDLE      -"
            }
            ExoPlayer.STATE_BUFFERING -> {
                stateString = "ExoPlayer.STATE_BUFFERING -"
            }
            ExoPlayer.STATE_READY -> {
                stateString = "ExoPlayer.STATE_READY     -"
                _uiState.update { it.copy(fileLoaded = true) }
            }
            ExoPlayer.STATE_ENDED -> {
                stateString = "ExoPlayer.STATE_ENDED     -"
                eventsChannel.trySend(PlayerEvents.NavigateBack)
            }
        }
        Timber.d("Changed player state to $stateString")
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing Player ViewModel")
        releasePlayer()
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        markUserInteraction()
        lastAppliedPreferenceMediaId = player.currentMediaItem?.mediaId
        saveTrackPreference(trackType, index)
        // Index -1 equals disable track
        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
        } else {
            val filteredGroups = player.currentTracks.groups
                .filter { it.type == trackType && it.isSupported }
            val group = filteredGroups.getOrNull(index) ?: return
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            group.mediaTrackGroup,
                            0,
                        )
                    )
                    .setTrackTypeDisabled(trackType, false)
                    .build()
        }
    }

    fun selectSpeed(speed: Float) {
        markUserInteraction()
        player.setPlaybackSpeed(speed)
        playbackSpeed = speed
    }

    private fun findBestAudioTrackMatch(
        audioGroups: List<Tracks.Group>,
        canonicalTracks: List<PreferenceTrack>,
        preference: ItemPreferenceDto,
    ): Tracks.Group? {
        if (preference.audioLanguage == null && preference.audioTitle == null) return null

        val canonicalTrack = TrackPreferenceMatcher.findCanonicalTrack(
            tracks = canonicalTracks,
            streamIndex = preference.audioIndex,
            language = preference.audioLanguage,
            title = preference.audioTitle,
            isForced = null,
        )
        if (canonicalTrack != null) {
            val playerIndex = TrackPreferenceMatcher.findPlayerTrackIndex(
                target = canonicalTrack,
                tracks = audioGroups.map { it.toPlayerTrackDescriptor() },
            )
            if (playerIndex != null) return audioGroups[playerIndex]
        }

        val targetLang = preference.audioLanguage?.takeIf { it.isNotBlank() }
        val targetTitle = preference.audioTitle?.takeIf { it.isNotBlank() }

        // Priority 1: Language + Title
        if (targetLang != null && targetTitle != null) {
            val match = audioGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang) && format.label?.equals(targetTitle, ignoreCase = true) == true
            }
            if (match != null) return match
        }

        // Priority 2: Language only
        if (targetLang != null) {
            val match = audioGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang)
            }
            if (match != null) return match
        }

        // Priority 3: Title only
        if (targetTitle != null) {
            val match = audioGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                format.label?.equals(targetTitle, ignoreCase = true) == true
            }
            if (match != null) return match
        }

        return null
    }

    private fun findBestSubtitleTrackMatch(
        textGroups: List<Tracks.Group>,
        canonicalTracks: List<PreferenceTrack>,
        preference: ItemPreferenceDto,
        isEpisode: Boolean,
    ): Tracks.Group? {
        if (preference.subtitleLanguage == "none" ||
            (preference.subtitleLanguage == null && preference.subtitleTitle == null)
        ) {
            return null
        }

        val targetLang = preference.subtitleLanguage?.takeIf { it.isNotBlank() }
        val targetTitle = preference.subtitleTitle?.takeIf { it.isNotBlank() }
        val targetForced = preference.subtitleIsForced
        val canonicalTrack = TrackPreferenceMatcher.findCanonicalTrack(
            tracks = canonicalTracks,
            streamIndex = if (isEpisode) null else preference.subtitleIndex,
            language = targetLang,
            title = targetTitle,
            isForced = targetForced,
        )
        if (canonicalTrack != null) {
            val playerIndex = TrackPreferenceMatcher.findPlayerTrackIndex(
                target = canonicalTrack,
                tracks = textGroups.map { it.toPlayerTrackDescriptor() },
            )
            if (playerIndex != null) return textGroups[playerIndex]
        }

        fun isFormatForced(format: Format): Boolean {
            return (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                    (format.label?.contains("forced", ignoreCase = true) == true)
        }

        // Priority 1: Language + Title + Forced
        if (targetLang != null && targetTitle != null && targetForced != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang) &&
                        format.label?.equals(targetTitle, ignoreCase = true) == true &&
                        isFormatForced(format) == targetForced
            }
            if (match != null) return match
        }

        // Priority 2: Language + Forced
        if (targetLang != null && targetForced != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang) && isFormatForced(format) == targetForced
            }
            if (match != null) return match
        }

        // Priority 3: Language + Title
        if (targetLang != null && targetTitle != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang) && format.label?.equals(targetTitle, ignoreCase = true) == true
            }
            if (match != null) return match
        }

        // Priority 4: Language only
        if (targetLang != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                languageTagsMatch(format.language, targetLang)
            }
            if (match != null) return match
        }

        // Priority 4.5: Title + Forced
        if (targetTitle != null && targetForced != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                format.label?.equals(targetTitle, ignoreCase = true) == true && isFormatForced(format) == targetForced
            }
            if (match != null) return match
        }

        // Priority 5: Title only
        if (targetTitle != null) {
            val match = textGroups.find { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                format.label?.equals(targetTitle, ignoreCase = true) == true
            }
            if (match != null) return match
        }

        return null
    }

    private fun Tracks.Group.toPlayerTrackDescriptor(): PlayerTrackDescriptor {
        val format = mediaTrackGroup.getFormat(0)
        return PlayerTrackDescriptor(
            title = format.label,
            language = format.language,
            isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                format.label?.contains("forced", ignoreCase = true) == true,
            isExternal = (format.roleFlags and C.ROLE_FLAG_AUXILIARY) != 0,
            codec = format.codecs,
            channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
        )
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        viewModelScope.launch {
            val mediaId = player.currentMediaItem?.mediaId ?: return@launch
            if (lastAppliedPreferenceMediaId == mediaId) return@launch

            val itemId = try { UUID.fromString(mediaId) } catch (e: Exception) { return@launch }
            val currentItem = items.find { it.itemId == itemId }
            val groupingId = currentItem?.groupingId ?: getGroupingId(itemId)
            val isEpisode = currentItem?.isEpisode ?: (groupingId != itemId)
            val preference = repository.getItemPreference(groupingId) ?: run {
                lastAppliedPreferenceMediaId = mediaId
                return@launch
            }

            val parametersBuilder = player.trackSelectionParameters.buildUpon()
            var changed = false
            var preferenceResolved = true

            // Audio matching
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            val bestAudio = findBestAudioTrackMatch(
                audioGroups = audioGroups,
                canonicalTracks = currentItem?.audioPreferenceTracks.orEmpty(),
                preference = preference,
            )
            if (bestAudio != null && !bestAudio.isSelected) {
                parametersBuilder.setOverrideForType(TrackSelectionOverride(bestAudio.mediaTrackGroup, 0))
                changed = true
            }

            // Subtitle matching
            if (preference.subtitleLanguage == "none") {
                if (!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                    parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    changed = true
                }
            } else if (preference.subtitleLanguage != null || preference.subtitleTitle != null) {
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                val bestSubtitle = findBestSubtitleTrackMatch(
                    textGroups = textGroups,
                    canonicalTracks = currentItem?.subtitlePreferenceTracks.orEmpty(),
                    preference = preference,
                    isEpisode = isEpisode,
                )
                if (bestSubtitle == null) {
                    // External subtitles are added asynchronously by MPV. Keep listening for
                    // track changes until the requested track is actually available.
                    preferenceResolved = false
                } else if (!bestSubtitle.isSelected) {
                    parametersBuilder.setOverrideForType(TrackSelectionOverride(bestSubtitle.mediaTrackGroup, 0))
                    parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    changed = true
                }
            }

            if (preferenceResolved) {
                lastAppliedPreferenceMediaId = mediaId
            }
            if (changed) {
                Timber.d("Applying track overrides for groupingId=$groupingId (isEpisode=$isEpisode)")
                player.trackSelectionParameters = parametersBuilder.build()
            }
        }
    }

    private fun saveTrackPreference(trackType: @C.TrackType Int, index: Int) {
        val previousWrite = lastPreferenceWriteJob
        lastPreferenceWriteJob = viewModelScope.launch(NonCancellable) {
            // Preserve selection order and finish the short local write even if the player closes.
            previousWrite?.join()
            val mediaId = player.currentMediaItem?.mediaId ?: return@launch
            val itemId = UUID.fromString(mediaId)
            val currentItem = items.find { it.itemId == itemId }
            val groupingId = currentItem?.groupingId ?: getGroupingId(itemId)
            val isEpisode = currentItem?.isEpisode ?: (groupingId != itemId)

            var preference = repository.getItemPreference(groupingId) ?: ItemPreferenceDto(id = groupingId)

            if (trackType == C.TRACK_TYPE_AUDIO) {
                if (index == -1) {
                    preference = preference.copy(audioLanguage = null, audioTitle = null, audioIndex = null)
                } else {
                    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                    val group = groups.getOrNull(index)
                    val format = group?.mediaTrackGroup?.getFormat(0)
                    val canonicalTrack = group?.let {
                        TrackPreferenceMatcher.findCanonicalTrackForPlayer(
                            playerTrack = it.toPlayerTrackDescriptor(),
                            tracks = currentItem?.audioPreferenceTracks.orEmpty(),
                        )
                    }
                    preference = preference.copy(
                        audioLanguage = canonicalTrack?.language
                            ?: format?.language?.takeIf { it.isNotBlank() }
                            ?: "und",
                        audioTitle = canonicalTrack?.title
                            ?: format?.label?.takeIf { it.isNotBlank() },
                        audioIndex = if (isEpisode) null else canonicalTrack?.index
                    )
                }
            } else if (trackType == C.TRACK_TYPE_TEXT) {
                if (index == -1) {
                    preference = preference.copy(
                        subtitleLanguage = "none",
                        subtitleTitle = null,
                        subtitleIndex = null,
                        subtitleIsForced = null
                    )
                } else {
                    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                    val group = groups.getOrNull(index)
                    val format = group?.mediaTrackGroup?.getFormat(0)
                    val canonicalTrack = group?.let {
                        TrackPreferenceMatcher.findCanonicalTrackForPlayer(
                            playerTrack = it.toPlayerTrackDescriptor(),
                            tracks = currentItem?.subtitlePreferenceTracks.orEmpty(),
                        )
                    }
                    val isForced = canonicalTrack?.isForced ?: format?.let {
                        (it.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
                                (it.label?.contains("forced", ignoreCase = true) == true)
                    } ?: false

                    preference = preference.copy(
                        subtitleLanguage = canonicalTrack?.language
                            ?: format?.language?.takeIf { it.isNotBlank() }
                            ?: "und",
                        subtitleTitle = canonicalTrack?.title
                            ?: format?.label?.takeIf { it.isNotBlank() },
                        subtitleIndex = if (isEpisode) null else canonicalTrack?.index,
                        subtitleIsForced = isForced
                    )
                }
            }
            repository.insertItemPreference(preference)
        }
    }

    suspend fun awaitPendingPreferenceWrites() {
        lastPreferenceWriteJob?.join()
    }

    private suspend fun getGroupingId(itemId: UUID): UUID {
        return try {
            val item = repository.getItem(itemId)
            if (item is FindroidEpisode) {
                item.seriesId
            } else {
                itemId
            }
        } catch (e: Exception) {
            itemId
        }
    }

    private suspend fun getSegments(itemId: UUID) {
        try {
            currentMediaItemSegments = repository.getSegments(itemId)
        } catch (e: Exception) {
            currentMediaItemSegments = emptyList()
            Timber.e(e)
        }
    }

    private suspend fun getTrickplay(item: PlayerItem) {
        val trickplayInfo = item.trickplayInfo ?: return
        Timber.d("Trickplay Resolution: ${trickplayInfo.width}")

        // Developer-toggled lazy path: hand the consumer a loader instead of eagerly
        // decoding the whole strip. The legacy path stays the default because it has
        // had more bake time.
        if (appPreferences.getValue(appPreferences.developerEnableTrickplay)) {
            val loader =
                TrickplayLoader(
                    repository = repository,
                    itemId = item.itemId,
                    width = trickplayInfo.width,
                    height = trickplayInfo.height,
                    tileWidth = trickplayInfo.tileWidth,
                    tileHeight = trickplayInfo.tileHeight,
                    thumbnailCount = trickplayInfo.thumbnailCount,
                    interval = trickplayInfo.interval,
                )
            _uiState.update {
                it.copy(
                    currentTrickplay = Trickplay(trickplayInfo.interval, loader = loader),
                )
            }
            return
        }

        withContext(Dispatchers.Default) {
            val maxIndex =
                ceil(
                        trickplayInfo.thumbnailCount
                            .toDouble()
                            .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                    )
                    .toInt()
            val bitmaps = mutableListOf<Bitmap>()

            for (i in 0..maxIndex) {
                repository.getTrickplayData(item.itemId, trickplayInfo.width, i)?.let { byteArray ->
                    val fullBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    try {
                        for (offsetY in
                            0..<trickplayInfo.height * trickplayInfo.tileHeight step
                                trickplayInfo.height) {
                            for (offsetX in
                                0..<trickplayInfo.width * trickplayInfo.tileWidth step
                                    trickplayInfo.width) {
                                val bitmap =
                                    Bitmap.createBitmap(
                                        fullBitmap,
                                        offsetX,
                                        offsetY,
                                        trickplayInfo.width,
                                        trickplayInfo.height,
                                    )
                                bitmaps.add(bitmap)
                            }
                        }
                    } finally {
                        fullBitmap.recycle()
                    }
                }
            }
            _uiState.update {
                it.copy(currentTrickplay = Trickplay(trickplayInfo.interval, bitmaps))
            }
        }
    }

    fun skipSegment(segment: FindroidSegment) {
        markUserInteraction()
        if (shouldSkipToNextEpisode(segment)) {
            player.seekToNextMediaItem()
        } else {
            player.seekTo(segment.endTicks)
        }
        _uiState.update { it.copy(currentSegment = null) }
    }

    // Check if the outro segment's end time is within n milliseconds of the player's total duration
    private fun shouldSkipToNextEpisode(segment: FindroidSegment): Boolean {
        return if (segment.type == FindroidSegmentType.OUTRO && player.hasNextMediaItem()) {
            val segmentEndTimeMillis = segment.endTicks
            val playerDurationMillis = player.duration
            val thresholdMillis =
                playerDurationMillis -
                    appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold)

            segmentEndTimeMillis > thresholdMillis
        } else {
            false
        }
    }

    private fun getSkipButtonTextStringId(segment: FindroidSegment): Int {
        return when (shouldSkipToNextEpisode(segment)) {
            true -> R.string.player_controls_next_episode
            false ->
                when (segment.type) {
                    FindroidSegmentType.INTRO -> R.string.player_controls_skip_intro
                    FindroidSegmentType.OUTRO -> R.string.player_controls_skip_outro
                    FindroidSegmentType.RECAP -> R.string.player_controls_skip_recap
                    FindroidSegmentType.COMMERCIAL -> R.string.player_controls_skip_commercial
                    FindroidSegmentType.PREVIEW -> R.string.player_controls_skip_preview
                    else -> R.string.player_controls_skip_unknown
                }
        }
    }

    /**
     * Get chapters of current item
     *
     * @return list of [PlayerChapter]
     */
    private fun getChapters(): List<PlayerChapter> {
        return uiState.value.currentChapters
    }

    /**
     * Get the index of the current chapter
     *
     * @return the index of the current chapter
     */
    private fun getCurrentChapterIndex(): Int? {
        val chapters = getChapters()

        for (i in chapters.indices.reversed()) {
            if (chapters[i].startPosition < player.currentPosition) {
                return i
            }
        }

        return null
    }

    /**
     * Get the index of the next chapter
     *
     * @return the index of the next chapter
     */
    private fun getNextChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        return minOf(chapters.size - 1, currentChapterIndex + 1)
    }

    /**
     * Get the index of the previous chapter. Only use this for seeking as it will return the
     * current chapter when player position is more than 5 seconds past the start of the chapter
     *
     * @return the index of the previous chapter
     */
    private fun getPreviousChapterIndex(): Int? {
        val chapters = getChapters()
        val currentChapterIndex = getCurrentChapterIndex() ?: return null

        // Return current chapter when more than 5 seconds past chapter start
        if (player.currentPosition > chapters[currentChapterIndex].startPosition + 5000L) {
            return currentChapterIndex
        }

        return maxOf(0, currentChapterIndex - 1)
    }

    fun isLastChapter(): Boolean =
        getChapters().let { chapters -> getCurrentChapterIndex() == chapters.size - 1 }

    /**
     * Seek to chapter
     *
     * @param [chapterIndex] the index of the chapter to seek to
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToChapter(chapterIndex: Int): PlayerChapter? {
        return getChapters().getOrNull(chapterIndex)?.also { chapter ->
            player.seekTo(chapter.startPosition)
        }
    }

    fun onAction(action: PlayerAction) {
        when (action) {
            is PlayerAction.JumpToChapter -> {
                seekToChapter(action.index)
            }
        }
    }

    /**
     * Build the URL for a chapter thumbnail. Returns `null` when no item is loaded yet, when the
     * index is out of bounds, or when the server did not extract a thumbnail for this chapter.
     */
    fun chapterImageUrl(chapterIndex: Int): String? {
        val state = uiState.value
        val itemId = state.currentItemId ?: return null
        val chapter = state.currentChapters.getOrNull(chapterIndex) ?: return null
        val tag = chapter.imageTag ?: return null
        val base = repository.getBaseUrl().trimEnd('/')
        if (base.isEmpty()) return null
        return "$base/Items/$itemId/Images/Chapter/$chapterIndex?tag=$tag"
    }

    /**
     * Seek to the next chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToNextChapter(): PlayerChapter? {
        markUserInteraction()
        return getNextChapterIndex()?.let { seekToChapter(it) }
    }

    /**
     * Seek to the previous chapter Will seek to start of current chapter if player position is more
     * than 5 seconds past start of chapter
     *
     * @return the [PlayerChapter] which has been sought to
     */
    fun seekToPreviousChapter(): PlayerChapter? {
        markUserInteraction()
        return getPreviousChapterIndex()?.let { seekToChapter(it) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        eventsChannel.trySend(PlayerEvents.IsPlayingChanged(isPlaying))
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        // Player.DISCONTINUITY_REASON_SEEK fires for user-driven seeks (controls + gestures both
        // end up here). It ALSO fires for our own auto-advance: with pauseAtEndOfMediaItems the
        // app performs the transition to the next item via an explicit seekToNextMediaItem(), which
        // the player reports as a seek, not as an AUTO_TRANSITION. autoAdvanceToNextItem() arms
        // pendingAutoAdvanceSeek before that call so we can tell the two apart here — a programmatic
        // advance must not count as a user interaction (that would reset the still-watching counter
        // and refresh the inactivity timer, so the prompt could never fire).
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            if (pendingAutoAdvanceSeek) {
                pendingAutoAdvanceSeek = false
            } else {
                markUserInteraction()
            }
        }
    }
}

sealed interface PlayerEvents {
    data object NavigateBack : PlayerEvents

    data class IsPlayingChanged(val isPlaying: Boolean) : PlayerEvents

    data class PlayerError(val error: PlaybackException) : PlayerEvents
}

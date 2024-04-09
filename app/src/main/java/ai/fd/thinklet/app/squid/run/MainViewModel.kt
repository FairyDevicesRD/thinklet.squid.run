package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.maintenance.camera.Angle
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.Camera2Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val angle: Angle by lazy(LazyThreadSafetyMode.NONE, ::Angle)

    val streamUrl: String? = savedState.get<String>("streamUrl")
    val streamKey: String? = savedState.get<String>("streamKey")
    private val longSide: Int = savedState.get<Int>("longSide") ?: 720
    private val shortSide: Int = savedState.get<Int>("shortSide") ?: 480
    private val orientation: Orientation? =
        Orientation.fromArgumentValue(savedState.get<String>("orientation"))
    val videoBitrateBps: Int =
        savedState.get<Int>("videoBitrate")?.let { it * 1024 } ?: DEFAULT_VIDEO_BITRATE_BPS
    val audioSampleRateHz: Int =
        savedState.get<Int>("audioSampleRate") ?: DEFAULT_AUDIO_SAMPLING_RATE_HZ
    val audioBitrateBps: Int =
        savedState.get<Int>("audioBitrate")?.let { it * 1024 } ?: DEFAULT_AUDIO_BITRATE_BPS
    val audioChannel: AudioChannel =
        AudioChannel.fromArgumentValue(savedState.get<String>("audioChannel"))
            ?: AudioChannel.STEREO
    val micMode: MicMode =
        MicMode.fromArgumentValue(savedState.get<String>("micMode"))
            ?: MicMode.ANDROID
    val isEchoCancelerEnabled: Boolean = savedState.get<Boolean>("echoCanceler") ?: false
    val shouldShowPreview: Boolean = savedState.get<Boolean>("preview") ?: false

    private val _isPrepared: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isPrepared: StateFlow<Boolean> = _isPrepared.asStateFlow()

    private val _isStreaming: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val streamingEventMutableSharedFlow: MutableSharedFlow<StreamingEvent> =
        MutableSharedFlow(replay = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val latestStreamingEventList: Flow<List<String>> = streamingEventMutableSharedFlow
        .map { DateTimeFormatter.ISO_LOCAL_TIME.format(it.timestamp) + " " + it.message }
        .runningFold(ArrayList(STREAMING_EVENT_BUFFER_SIZE)) { acc, value ->
            acc.add(value)
            if (acc.size > STREAMING_EVENT_BUFFER_SIZE) {
                acc.removeAt(0)
            }
            acc
        }

    val width: Int
        get() = if (angle.isPortrait()) shortSide else longSide

    val height: Int
        get() = if (angle.isPortrait()) longSide else shortSide

    private var stream: GenericStream? = null

    private var startPreviewJob: Job? = null

    init {
        when (orientation) {
            Orientation.LANDSCAPE -> angle.setLandscape()
            Orientation.PORTRAIT -> angle.setPortrait()
            null -> Unit
        }
    }

    fun isAllPermissionGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
    }

    @MainThread
    fun maybePrepareStreaming() {
        if (streamUrl == null || streamKey == null || !isAllPermissionGranted() || _isPrepared.value) {
            _isPrepared.value = false
            return
        }

        val angle = Angle()
        val camera2Source = Camera2Source(application)
        val audioSource = when (micMode) {
            MicMode.ANDROID -> MicrophoneSource()
            MicMode.THINKLET_5 -> ThinkletMicrophoneSource(
                application,
                MultiChannelAudioRecord.Channel.CHANNEL_FIVE
            )

            MicMode.THINKLET_6 -> ThinkletMicrophoneSource(
                application,
                MultiChannelAudioRecord.Channel.CHANNEL_SIX
            )
        }
        val localStream = stream ?: GenericStream(
            application,
            ConnectionCheckerImpl(streamingEventMutableSharedFlow),
            camera2Source,
            audioSource
        ).apply {
            getGlInterface().autoHandleOrientation = false
            if (angle.isLandscape()) {
                val rotateFilterRender = RotationFilterRender().apply {
                    this.rotation = 270
                }
                getGlInterface().addFilter(rotateFilterRender)
            }
        }
        val isPrepared = try {
            // Note: The output size is converted by 90 degrees inside RootEncoder when the rotation
            // is portrait. Therefore, we intentionally pass `longSide` and `shortSide` to `width`
            // and `height` respectively so that it will be output at the correct size.
            val isVideoPrepared = localStream.prepareVideo(
                width = longSide,
                height = shortSide,
                bitrate = videoBitrateBps,
                rotation = angle.current()
            )
            val isAudioPrepared = localStream.prepareAudio(
                sampleRate = audioSampleRateHz,
                isStereo = audioChannel == AudioChannel.STEREO,
                bitrate = audioBitrateBps,
                echoCanceler = isEchoCancelerEnabled
            )
            isVideoPrepared && isAudioPrepared
        } catch (e: IllegalArgumentException) {
            false
        }
        if (isPrepared) {
            stream = localStream
            _isPrepared.value = true
        } else {
            _isPrepared.value = false
        }
    }

    fun maybeStartStreaming(): Boolean {
        val isStreamingStarted = maybeStartStreamingInternal()
        _isStreaming.value = isStreamingStarted
        return isStreamingStarted
    }

    private fun maybeStartStreamingInternal(): Boolean {
        val streamSnapshot = stream
        if (streamUrl == null || streamKey == null || streamSnapshot == null) {
            return false
        }
        if (streamSnapshot.isStreaming) {
            return true
        }
        streamSnapshot.startStream("$streamUrl/$streamKey")
        return true
    }

    fun stopStreaming() {
        val streamSnapshot = stream ?: return
        streamSnapshot.stopStream()
        _isStreaming.value = false
    }

    @MainThread
    fun startPreview(surface: Surface, width: Int, height: Int) {
        val localStream = stream ?: return
        if (localStream.isOnPreview) {
            localStream.stopPreview()
        }

        if (startPreviewJob != null) {
            return
        }
        startPreviewJob = viewModelScope.launch {
            isPrepared.first { true }
            localStream.startPreview(surface, width, height)
        }
    }

    @MainThread
    fun stopPreview() {
        startPreviewJob?.cancel()
        val localStream = stream ?: return
        if (localStream.isOnPreview) {
            localStream.stopPreview()
        }
    }

    private class ConnectionCheckerImpl(
        private val streamingEventMutableSharedFlow: MutableSharedFlow<StreamingEvent>
    ) : ConnectChecker {
        override fun onAuthError() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onAuthError"))
        }

        override fun onAuthSuccess() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onAuthSuccess"))
        }

        override fun onConnectionFailed(reason: String) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionFailed: $reason"))
        }

        override fun onConnectionStarted(url: String) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionStarted: $url"))
        }

        override fun onConnectionSuccess() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onConnectionSuccess"))
        }

        override fun onDisconnect() {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onDisconnect"))
        }

        override fun onNewBitrate(bitrate: Long) {
            streamingEventMutableSharedFlow.tryEmit(StreamingEvent("onNewBitrate: $bitrate"))
        }
    }

    private data class StreamingEvent(
        val message: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    enum class Orientation(val argumentValue: String) {
        LANDSCAPE("landscape"), PORTRAIT("portrait");

        companion object {
            fun fromArgumentValue(value: String?): Orientation? =
                entries.find { it.argumentValue == value }
        }
    }

    enum class AudioChannel(val argumentValue: String) {
        MONAURAL("monaural"), STEREO("stereo");

        companion object {
            fun fromArgumentValue(value: String?): AudioChannel? =
                entries.find { it.argumentValue == value }
        }
    }

    enum class MicMode(val argumentValue: String) {
        ANDROID("android"),
        THINKLET_5("thinklet5"),
        THINKLET_6("thinklet6"),
        ;

        companion object {
            fun fromArgumentValue(value: String?): MicMode? =
                entries.find { it.argumentValue == value }
        }
    }

    companion object {
        private const val DEFAULT_VIDEO_BITRATE_BPS = 4 * 1024 * 1024 // 4Mbps
        private const val DEFAULT_AUDIO_SAMPLING_RATE_HZ = 44100 // 44.1kHz
        private const val DEFAULT_AUDIO_BITRATE_BPS = 128 * 1024 // 128kbps

        private const val STREAMING_EVENT_BUFFER_SIZE = 15

        private val REQUIRED_PERMISSIONS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
                .takeIf { Build.VERSION.SDK_INT <= Build.VERSION_CODES.P }
        )
    }
}

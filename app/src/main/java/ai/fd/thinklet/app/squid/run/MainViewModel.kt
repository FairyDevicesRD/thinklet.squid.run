package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.pedro.common.ConnectChecker
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.Camera2Source
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(
    private val application: Application,
    savedState: SavedStateHandle
) : AndroidViewModel(application) {

    val streamUrl: String? = savedState.get<String>("streamUrl")
    val streamKey: String? = savedState.get<String>("streamKey")
    val width: Int = savedState.get<Int>("width") ?: 720
    val height: Int = savedState.get<Int>("height") ?: 480
    val videoBitrateBps: Int =
        savedState.get<Int>("videoBitrate")?.let { it * 1024 } ?: DEFAULT_VIDEO_BITRATE_BPS
    val audioSampleRateHz: Int =
        savedState.get<Int>("audioSampleRate") ?: DEFAULT_AUDIO_SAMPLING_RATE_HZ
    val audioBitrateBps: Int =
        savedState.get<Int>("audioBitrate")?.let { it * 1024 } ?: DEFAULT_AUDIO_BITRATE_BPS

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

    private var stream: GenericStream? = null

    fun isAllPermissionGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
    }

    @MainThread
    fun maybePrepareStreaming() {
        if (streamUrl == null || streamKey == null || !isAllPermissionGranted() || _isPrepared.value) {
            _isPrepared.value = false
            return
        }

        val localStream = stream ?: GenericStream(
            application,
            ConnectionCheckerImpl(streamingEventMutableSharedFlow),
            Camera2Source(application),
            MicrophoneSource()
        )
        val isPrepared = try {
            val isVideoPrepared = localStream.prepareVideo(
                width = width,
                height = height,
                bitrate = videoBitrateBps
            )
            val isAudioPrepared = localStream.prepareAudio(
                sampleRate = audioSampleRateHz,
                isStereo = true,
                bitrate = audioBitrateBps
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

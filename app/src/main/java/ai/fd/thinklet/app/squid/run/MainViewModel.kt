package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.sdk.maintenance.camera.Angle
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.generic.GenericStream
import com.pedro.library.rtmp.RtmpCamera2
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

        val camera2Source = Camera2Source(application)
        val localStream = stream ?: GenericStream(
            application,
            ConnectionCheckerImpl(streamingEventMutableSharedFlow),
            camera2Source,
            MicrophoneSource()
        )
        val cameraMaxResolution = camera2Source.getCameraResolutions(CameraHelper.Facing.BACK)
            .maxBy { it.width * it.height }
        // Workaround to stream video at a specified size.
        // Looking for a way to achieve this in a pure way without using cropping.
        localStream.getGlInterface().setFilter(createCropFilter(cameraMaxResolution))
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

    private fun createCropFilter(cameraMaxResolution: Size): BaseFilterRender {
        val sourceSize = if (Angle().isLandscape()) {
            cameraMaxResolution
        } else {
            Size(cameraMaxResolution.height, cameraMaxResolution.width)
        }
        val sourceAspectRatio = sourceSize.width.toFloat() / sourceSize.height
        val destinationAspectRatio = width.toFloat() / height
        return if (sourceAspectRatio > destinationAspectRatio) {
            val requiredWidthPercent =
                (sourceSize.height * destinationAspectRatio) / sourceSize.width * 100
            CropFilterRender().apply {
                // Note: `height` should be `100` because use all of the height.
                // However, RootEncoder has a bug that causes zero division when `100` is passed,
                // so use a value as close to `100`.
                // `width` is also capped at `99.9999` due to same reason.
                setCropArea(
                    offsetX = (100f - requiredWidthPercent) / 2,
                    offsetY = 0f,
                    width = requiredWidthPercent.coerceAtMost(99.9999f),
                    height = 99.99999f
                )
            }
        } else {
            val requiredHeightPercent =
                (sourceSize.width / destinationAspectRatio) / sourceSize.height * 100
            CropFilterRender().apply {
                // Note: `width` should be `100` because use all of the width. However, RootEncoder
                // However, RootEncoder has a bug that causes zero division when `100` is passed,
                // so use a value as close to `100`.
                // `height` is also capped at `99.9999` due to same reason.
                setCropArea(
                    offsetX = 0f,
                    offsetY = (100f - requiredHeightPercent) / 2,
                    width = 99.99999f,
                    height = requiredHeightPercent.coerceAtMost(99.9999f)
                )
            }
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

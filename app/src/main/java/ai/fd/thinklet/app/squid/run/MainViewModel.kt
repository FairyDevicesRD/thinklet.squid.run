package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.pedro.common.ConnectChecker
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.video.Camera2Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            ConnectionCheckerImpl(),
            Camera2Source(application),
            MicrophoneSource()
        )
        val isPrepared = try {
            val isVideoPrepared = localStream.prepareVideo(width, height, videoBitrateBps)
            val isAudioPrepared = localStream.prepareAudio(audioSampleRateHz, true, audioBitrateBps)
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

    class ConnectionCheckerImpl : ConnectChecker {
        override fun onAuthError() {
            Log.d(TAG, "onAuthError")
        }

        override fun onAuthSuccess() {
            Log.d(TAG, "onAuthSuccess")
        }

        override fun onConnectionFailed(reason: String) {
            Log.d(TAG, "onConnectionFailed: $reason")
        }

        override fun onConnectionStarted(url: String) {
            Log.d(TAG, "onConnectionStarted: $url")
        }

        override fun onConnectionSuccess() {
            Log.d(TAG, "onConnectionSuccess")
        }

        override fun onDisconnect() {
            Log.d(TAG, "onDisconnect")
        }

        override fun onNewBitrate(bitrate: Long) {
            Log.d(TAG, "onNewBitrate: $bitrate")
        }

    }

    companion object {

        private const val TAG = "MainViewModel"

        private const val DEFAULT_VIDEO_BITRATE_BPS = 4 * 1024 * 1024 // 4Mbps
        private const val DEFAULT_AUDIO_SAMPLING_RATE_HZ = 44100 // 44.1kHz
        private const val DEFAULT_AUDIO_BITRATE_BPS = 128 * 1024 // 128kbps

        private val REQUIRED_PERMISSIONS = listOfNotNull(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
                .takeIf { Build.VERSION.SDK_INT <= Build.VERSION_CODES.P }
        )
    }
}

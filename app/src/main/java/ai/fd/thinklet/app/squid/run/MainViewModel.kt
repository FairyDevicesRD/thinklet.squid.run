package ai.fd.thinklet.app.squid.run

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle

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

    fun isAllPermissionGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {

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

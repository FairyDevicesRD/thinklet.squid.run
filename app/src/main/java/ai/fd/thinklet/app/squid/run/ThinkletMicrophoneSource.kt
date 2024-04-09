package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.sdk.audio.MultiChannelAudioRecord
import ai.fd.thinklet.sdk.audio.RawAudioRecordWrapper
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.library.util.sources.audio.AudioSource
import java.io.OutputStream

class ThinkletMicrophoneSource(
    private val context: Context,
    private val inputChannel: MultiChannelAudioRecord.Channel
) : AudioSource() {

    private var bridge: RawAudioRecordWrapperBridge? = null

    // Note: No items that can be checked in advance.
    override fun create(
        sampleRate: Int,
        isStereo: Boolean,
        echoCanceler: Boolean,
        noiseSuppressor: Boolean
    ): Boolean = true

    override fun start(getMicrophoneData: GetMicrophoneData) {
        val newBridge = RawAudioRecordWrapperBridge(
            inputChannel = inputChannel,
        ) { frame ->
            getMicrophoneData.inputPCMData(frame)
        }
        val isStarted = newBridge.start(context, sampleRate, isStereo, echoCanceler)
        if (!isStarted) {
            throw IllegalArgumentException("Failed to create audio source")
        }
        bridge = newBridge
    }

    override fun stop() {
        if (isRunning()) {
            bridge?.stop()
            bridge = null
        }
    }

    override fun isRunning(): Boolean = bridge?.isRunning() ?: false

    override fun release() = stop()

    // Note: Copied from [MultiChannelAudioRecord.get].
    override fun getMaxInputSize(): Int = BUFFER_SIZE_IN_BYTES * (sampleRate / SAMPLING_RATE_MIN)

    override fun setMaxInputSize(size: Int) = Unit

    private class RawAudioRecordWrapperBridge(
        private val inputChannel: MultiChannelAudioRecord.Channel,
        private val onNewFrameArrived: (frame: Frame) -> Unit
    ) {

        private var rawAudioRecordWrapper: RawAudioRecordWrapper? = null

        @SuppressLint("MissingPermission")
        fun start(
            context: Context,
            sampleRate: Int,
            isStereo: Boolean,
            isEchoCancelerEnabled: Boolean
        ): Boolean {
            val multiMicAwareSampleRate = when (sampleRate) {
                16000 -> MultiChannelAudioRecord.SampleRate.SAMPLING_RATE_16000
                32000 -> MultiChannelAudioRecord.SampleRate.SAMPLING_RATE_32000
                48000 -> MultiChannelAudioRecord.SampleRate.SAMPLING_RATE_48000
                else -> {
                    Log.d(TAG, "Unsupported sample rate: $sampleRate")
                    return false
                }
            }
            val outputChannel = if (isStereo) {
                RawAudioRecordWrapper.RawAudioOutputChannel.STEREO
            } else {
                RawAudioRecordWrapper.RawAudioOutputChannel.MONO
            }
            val rawAudioRecordWrapper = RawAudioRecordWrapper(
                channel = inputChannel,
                sampleRate = multiMicAwareSampleRate,
                outputChannel = outputChannel
            )
            if (!rawAudioRecordWrapper.prepare(context)) {
                Log.d(TAG, "Failed to prepare RawAudioRecordWrapper")
                return false
            }
            val listener = object : RawAudioRecordWrapper.IRawAudioRecorder {
                override fun onFailed(throwable: Throwable) {
                    Log.d(TAG, "Failed to receive PCM data", throwable)
                }

                override fun onReceivedPcmData(pcmData: ByteArray) {
                    val timeStamp = System.nanoTime() / 1000L
                    onNewFrameArrived(Frame(pcmData, 0, pcmData.size, timeStamp))
                }
            }
            this.rawAudioRecordWrapper = rawAudioRecordWrapper
            rawAudioRecordWrapper.start(NullOutputStream(), listener, isEchoCancelerEnabled)
            return true
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            rawAudioRecordWrapper?.stop()
            rawAudioRecordWrapper = null
        }

        fun isRunning(): Boolean = rawAudioRecordWrapper != null

        companion object {
            private const val TAG = "RawAudioRecordWrapperBridge"
        }
    }

    private class NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray?) = Unit
        override fun write(b: ByteArray?, off: Int, len: Int) = Unit
    }

    companion object {
        private const val BUFFER_SIZE_IN_BYTES = 1920
        private const val SAMPLING_RATE_MIN = 16000
    }
}

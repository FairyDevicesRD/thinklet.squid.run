package ai.fd.thinklet.app.squid.run

import ai.fd.thinklet.app.squid.run.databinding.ActivityMainBinding
import ai.fd.thinklet.app.squid.run.databinding.PreviewBinding
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibrationEffect.DEFAULT_AMPLITUDE
import android.os.Vibrator
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val vibrator: Vibrator by lazy(LazyThreadSafetyMode.NONE) {
        checkNotNull(getSystemService())
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.streamUrl.text = viewModel.streamUrl
        binding.streamKey.text = viewModel.streamKey
        binding.dimension.text =
            getString(R.string.dimension_text, viewModel.width, viewModel.height)
        binding.videoBitrate.text = (viewModel.videoBitrateBps / 1024).toString()
        binding.samplingRate.text = (viewModel.audioSampleRateHz / 1000f).toString()
        binding.audioBitrate.text = (viewModel.audioBitrateBps / 1024).toString()
        binding.permissionGranted.text = viewModel.isAllPermissionGranted().toString()

        if (viewModel.shouldShowPreview) {
            val previewBinding = PreviewBinding.bind(binding.previewStub.inflate())
            previewBinding.preview.updateLayoutParams<ConstraintLayout.LayoutParams> {
                dimensionRatio = "${viewModel.width}:${viewModel.height}"
            }
            previewBinding.preview.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) = viewModel.startPreview(holder.surface, width, height)

                override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.stopPreview()
            })
        }

        lifecycleScope.launch {
            viewModel.isPrepared
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streamPrepared.text = it.toString()
                }
        }
        lifecycleScope.launch {
            viewModel.isStreaming
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streaming.text = it.toString()
                }
        }
        lifecycleScope.launch {
            viewModel.latestStreamingEventList
                .flowWithLifecycle(lifecycle)
                .collect {
                    binding.streamingEvents.text = it.asReversed().joinToString("\n")
                }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeNotifyLaunchErrors()
        viewModel.maybePrepareStreaming()
    }

    override fun onPause() {
        viewModel.stopStreaming()
        super.onPause()
    }

    private fun maybeNotifyLaunchErrors() {
        if (!viewModel.isAllPermissionGranted()) {
            vibrator.vibrate(createStaccatoVibrationEffect(2))
            return
        }
        if (viewModel.streamUrl == null || viewModel.streamKey == null) {
            vibrator.vibrate(createStaccatoVibrationEffect(3))
            return
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                toggleStreaming()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleStreaming() {
        if (!viewModel.isStreaming.value) {
            val isStreamingStarted = viewModel.maybeStartStreaming()
            if (!isStreamingStarted) {
                vibrator.vibrate(createStaccatoVibrationEffect(2))
            }
        } else {
            viewModel.stopStreaming()
        }
    }

    private fun createStaccatoVibrationEffect(staccatoCount: Int): VibrationEffect {
        val timing = LongArray(staccatoCount * 2) { 200L }
        val amplitudes = IntArray(staccatoCount * 2) { i ->
            if (i % 2 == 0) 0 else DEFAULT_AMPLITUDE
        }
        return VibrationEffect.createWaveform(timing, amplitudes, -1)
    }
}

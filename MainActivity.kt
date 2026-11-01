package com.example.surround

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.PlayerView
import kotlin.math.abs

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var front: ExoPlayer   // video + front stereo -> mirrored to TV
    private lateinit var rear: ExoPlayer    // rear L/R only -> wired aux
    private var delayMs = 150L
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    // 5.1 PCM order: FL FR FC LFE BL BR. Matrix is [input*2 + output].
    private fun mixer(isFront: Boolean) = ChannelMixingAudioProcessor().apply {
        val m6 = FloatArray(12)
        if (isFront) { m6[0] = 1f; m6[3] = 1f; m6[4] = .7f; m6[5] = .7f; m6[6] = .5f; m6[7] = .5f }
        else { m6[8] = 1f; m6[11] = 1f }
        putChannelMixingMatrix(ChannelMixingMatrix(6, 2, m6))
        putChannelMixingMatrix(ChannelMixingMatrix(2, 2, if (isFront) floatArrayOf(1f, 0f, 0f, 1f) else FloatArray(4)))
    }

    private fun build(isFront: Boolean): ExoPlayer {
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(c: Context, f: Boolean, p: Boolean): AudioSink =
                DefaultAudioSink.Builder(c).setAudioProcessors(arrayOf(mixer(isFront))).build()
        }
        return ExoPlayer.Builder(this, rf).build().also { it.setAudioAttributes(AudioAttributes.DEFAULT, false) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::load) }

    private fun load(uri: Uri) {
        val am = getSystemService(AudioManager::class.java)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in wired }
        rear.setPreferredAudioDevice(dev)
        status.text = if (dev != null) "Rear -> ${dev.productName}" else "No wired/USB audio out found: plug in the aux adapter, then pick the file again"
        for (p in listOf(front, rear)) { p.setMediaItem(MediaItem.fromUri(uri)); p.prepare() }
        rear.playWhenReady = false
        front.playWhenReady = true
    }

    private val tick = object : Runnable {
        override fun run() {
            val target = front.currentPosition - delayMs
            if (!front.isPlaying || target < 0) rear.pause()
            else {
                if (abs(rear.currentPosition - target) > 60) rear.seekTo(target)
                if (!rear.isPlaying) rear.play()
            }
            h.postDelayed(this, 400)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        front = build(true); rear = build(false)
        rear.trackSelectionParameters = rear.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        val pv = PlayerView(this).apply { player = front; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        status = TextView(this).apply { text = "Start screen mirroring (Smart View) first, then pick a video" }
        val label = TextView(this).apply { text = "Rear delay: 150 ms" }
        val seek = SeekBar(this).apply {
            max = 1000; progress = 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { delayMs = v.toLong(); label.text = "Rear delay: $v ms" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val pick = Button(this).apply { text = "Pick video"; setOnClickListener { picker.launch(arrayOf("video/*")) } }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(pv); addView(pick); addView(label); addView(seek); addView(status) })
        h.post(tick)
    }

    override fun onDestroy() { h.removeCallbacks(tick); front.release(); rear.release(); super.onDestroy() }
}

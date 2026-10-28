package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class AudioPlayerManager(private val context: Context) {
    private var audioTrack: AudioTrack? = null
    private var instrumentalTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private data class InstrumentalFrame(val bytes: ByteArray)
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val instrumentalChannel = Channel<InstrumentalFrame>(Channel.UNLIMITED)
    @Volatile private var instrumentalSyncGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var playbackJob: Job? = null
    private var instrumentalJob: Job? = null

    private var initialMusicVolume: Int = -1
    private var maxMusicVolume: Int = 15

    companion object {
        private const val TAG = "AudioPlayerManager"
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var isDubbingMuted: Boolean = false
    @Volatile private var currentVoicePersona: String = "Puck"
    @Volatile private var currentPlaybackSpeed: Float = 1.0f
    @Volatile private var instrumentalVolume: Float = 0.65f

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed.coerceIn(0.75f, 1.35f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val track = audioTrack
                if (track != null) {
                    track.playbackParams = track.playbackParams.setSpeed(currentPlaybackSpeed)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback speed change unavailable on this device", e)
        }
    }

    fun setVoicePersona(persona: String) {
        currentVoicePersona = persona
        Log.d(TAG, "Active voice persona set to: $persona")
    }

    fun start() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        try {
            initialMusicVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            maxMusicVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        } catch (e: Exception) {
            Log.w(TAG, "Could not query STREAM_MUSIC volume", e)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
                
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 4096 * 2)

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        setPlaybackSpeed(currentPlaybackSpeed)

        // Separate track for the Demucs instrumental stem. Android mixes this with
        // the translated voice track; original video audio can then be muted.
        val bgMinBuffer = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bgFormat = AudioFormat.Builder()
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        instrumentalTrack = AudioTrack.Builder()
            // Use ASSISTANT usage so muting STREAM_MUSIC to silence the original video
            // does not also mute this separately rendered instrumental stem.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Use the same assistant usage as the translated voice track.
                    // Some Android/OEM audio policies route USAGE_ASSISTANCE_ACCESSIBILITY
                    // to a muted accessibility stream, making the instrumental inaudible.
                    // The assistant track remains separate from the source app's media stream
                    // on devices that support per-usage volume routing.
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(bgFormat)
            .setBufferSizeInBytes(maxOf(bgMinBuffer, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        instrumentalTrack?.setVolume(instrumentalVolume)
        instrumentalTrack?.play()
        Log.i(TAG, "Instrumental AudioTrack initialized: state=${instrumentalTrack?.state}, playState=${instrumentalTrack?.playState}, sampleRate=${instrumentalTrack?.sampleRate}, volume=$instrumentalVolume")

        instrumentalJob = playerScope.launch {
            for (frame in instrumentalChannel) {
                try {
                    val track = instrumentalTrack ?: continue
                    val volume = instrumentalVolume.coerceIn(0f, 1f)
                    track.setVolume(volume)
                    // AudioTrack is allowed to perform partial writes. Continue until this
                    // PCM frame is fully queued; otherwise chunks can be silently truncated.
                    var offset = 0
                    while (offset < frame.bytes.size) {
                        val written = track.write(frame.bytes, offset, frame.bytes.size - offset)
                        if (written < 0) {
                            Log.e(TAG, "Instrumental AudioTrack.write failed: $written; state=${track.state}, playState=${track.playState}")
                            break
                        }
                        if (written == 0) {
                            kotlinx.coroutines.delay(10)
                        } else {
                            offset += written
                        }
                    }
                    if (offset == frame.bytes.size) {
                        Log.v(TAG, "Instrumental PCM frame played to AudioTrack queue: ${offset} bytes; playState=${track.playState}")
                    } else {
                        Log.w(TAG, "Instrumental PCM frame incomplete: wrote=$offset/${frame.bytes.size}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing instrumental audio", e)
                }
            }
        }

        playbackJob = playerScope.launch {
            var prevSample = 0f
            for (chunk in audioChannel) {
                if (isDubbingMuted || currentVolume <= 0.01f) {
                    continue
                }

                try {
                    // Direct software PCM scaling with logarithmic curve for 100% audible control on all devices
                    val vol = currentVolume.coerceIn(0f, 1f)
                    val gain = (vol * vol * 1.35f)
                    val persona = currentVoicePersona

                    val processedChunk = ByteArray(chunk.size)
                    val numSamples = chunk.size / 2

                    for (i in 0 until numSamples) {
                        val byteIndex = i * 2
                        val low = chunk[byteIndex].toInt() and 0xFF
                        val high = chunk[byteIndex + 1].toInt()
                        var sample = ((high shl 8) or low).toShort().toFloat()

                        // 1. Voice Persona Character Filtering (DSP timbre shaping)
                        sample = when (persona) {
                            "Charon" -> {
                                // Deep & Resonant: warm low-end boost and smooth high roll-off
                                val filtered = (sample * 0.70f) + (prevSample * 0.30f)
                                filtered * 1.08f
                            }
                            "Puck" -> {
                                // Energetic & Crisp: presence high-shelf boost
                                val diff = sample - prevSample
                                (sample * 0.85f) + (diff * 0.28f)
                            }
                            "Aoede" -> {
                                // Melodic & Warm: balanced harmonic warmth
                                (sample * 0.82f) + (prevSample * 0.18f)
                            }
                            "Fenrir" -> {
                                // Authoritative & Bold: firm dynamic presence
                                val diff = sample - prevSample
                                (sample * 0.90f) + (diff * 0.18f)
                            }
                            "Kore" -> {
                                // Gentle & Calm: gentle softening
                                (sample * 0.75f) + (prevSample * 0.25f)
                            }
                            else -> sample
                        }
                        prevSample = sample

                        // 2. Volume Gain Scaling with Soft Limiter
                        val scaled = (sample * gain).coerceIn(-32767f, 32767f).toInt()

                        processedChunk[byteIndex] = (scaled and 0xFF).toByte()
                        processedChunk[byteIndex + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }

                    audioTrack?.write(processedChunk, 0, processedChunk.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio chunk", e)
                }
            }
        }
    }

    fun beginInstrumentalSyncWindow() {
        // Keep the legacy API but do not gate background audio on translated speech.
        // Waiting for the first TTS frame could otherwise make the background silent
        // indefinitely when the translation service is slow or returns text only.
        instrumentalSyncGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    }

    fun playAudioData(data: ByteArray) {
        instrumentalSyncGate.complete(Unit)
        audioChannel.trySend(data)
    }

    fun playInstrumentalPcmData(data: ByteArray) {
        // Separate track is mixed by Android with the translated voice track.
        val result = instrumentalChannel.trySend(InstrumentalFrame(data))
        if (result.isFailure) Log.e(TAG, "Could not queue instrumental PCM frame: ${result.exceptionOrNull()?.message ?: "channel closed"}")
    }

    fun setInstrumentalVolume(volume: Float) {
        instrumentalVolume = volume.coerceIn(0f, 1f)
        try { instrumentalTrack?.setVolume(instrumentalVolume) } catch (e: Exception) {
            Log.w(TAG, "Unable to set instrumental volume", e)
        }
    }

    fun setDubbingAudio(volume: Float, isMuted: Boolean) {
        currentVolume = volume.coerceIn(0f, 1f)
        isDubbingMuted = isMuted
        val targetHardwareVolume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        try {
            audioTrack?.setVolume(targetHardwareVolume)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting dubbing volume", e)
        }
    }

    fun setOriginalMediaAudio(volumeRatio: Float, isMuted: Boolean) {
        try {
            val baseVol = if (initialMusicVolume > 0) initialMusicVolume else (maxMusicVolume * 0.7f).toInt()
            val targetVolume = if (isMuted) {
                0
            } else {
                (baseVol * volumeRatio.coerceIn(0f, 1f)).toInt().coerceIn(0, maxMusicVolume)
            }
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error adjusting STREAM_MUSIC volume", e)
        }
    }

    fun stop() {
        // Restore original system music volume
        if (initialMusicVolume >= 0) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, initialMusicVolume, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring STREAM_MUSIC volume", e)
            }
        }

        playbackJob?.cancel()
        playbackJob = null
        instrumentalJob?.cancel()
        instrumentalJob = null
        audioChannel.close()
        instrumentalChannel.close()
        playerScope.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus { }
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audioTrack", e)
        }
        audioTrack = null
        try {
            instrumentalTrack?.pause()
            instrumentalTrack?.flush()
            instrumentalTrack?.stop()
            instrumentalTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping instrumental track", e)
        }
        instrumentalTrack = null
    }
}

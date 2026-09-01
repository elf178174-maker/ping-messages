package com.ping.messenger.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/** A finished recording. */
data class Recording(
    val path: String,
    val durationMs: Long,
    /** Peak amplitudes normalised to 0..1, sampled while recording, for the waveform. */
    val waveform: List<Float>,
)

/**
 * Voice-message recording.
 *
 * AAC in an MP4 container at 32 kbit/s mono: about 4 kB per second, which is small enough to
 * send on a poor connection and still perfectly intelligible for speech. Amplitude is sampled
 * as it records so the waveform is real rather than decorative.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: MediaStorage,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private val amplitudes = mutableListOf<Float>()

    val isRecording: Boolean get() = recorder != null

    /** Returns false when the microphone is unavailable — usually a missing permission. */
    fun start(): Boolean {
        if (recorder != null) return true
        val file = storage.newRecordingFile()

        return try {
            val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(44_100)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            outputFile = file
            startedAt = System.currentTimeMillis()
            amplitudes.clear()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start recording", e)
            runCatching { file.delete() }
            recorder = null
            false
        }
    }

    /** Call periodically while recording to build the waveform. */
    fun sampleAmplitude() {
        val current = recorder ?: return
        val raw = runCatching { current.maxAmplitude }.getOrDefault(0)
        // maxAmplitude is linear 0..32767; a dB-style curve matches how loudness is perceived
        // and stops quiet speech from rendering as a flat line.
        val normalised = if (raw <= 0) {
            0f
        } else {
            (log10(raw.toDouble().coerceAtLeast(1.0)) / log10(32767.0)).toFloat()
        }
        amplitudes += normalised.coerceIn(0f, 1f)
    }

    /**
     * Stops recording. With [discard] the file is deleted and null returned, which is what a
     * slide-to-cancel gesture does.
     */
    fun stop(discard: Boolean = false): Recording? {
        val current = recorder ?: return null
        val file = outputFile
        recorder = null
        outputFile = null

        val stoppedCleanly = runCatching {
            current.stop()
        }.isSuccess
        runCatching { current.release() }

        if (discard || !stoppedCleanly || file == null || !file.exists() || file.length() == 0L) {
            runCatching { file?.delete() }
            return null
        }

        return Recording(
            path = file.absolutePath,
            durationMs = System.currentTimeMillis() - startedAt,
            waveform = amplitudes.toList(),
        )
    }

    private companion object {
        const val TAG = "AudioRecorder"
    }
}

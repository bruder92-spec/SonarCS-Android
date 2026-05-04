package com.sonar.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

internal class AudioRecorder {

    companion object {
        const val SAMPLE_RATE    = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        check(record == null) { "Already recording" }
        buffer.reset()
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING,
            minBuf * 4
        )
        running = true
        record!!.startRecording()
        Thread(::captureLoop, "SonarCapture").start()
    }

    fun stopRecording(): ByteArray {
        running = false
        record?.stop()
        record?.release()
        record = null
        return buffer.toByteArray()
    }

    private fun captureLoop() {
        val chunk = ByteArray(minBuf)
        while (running) {
            val n = record?.read(chunk, 0, chunk.size) ?: break
            if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
        }
    }
}

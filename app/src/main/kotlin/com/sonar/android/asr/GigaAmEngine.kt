package com.sonar.android.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.Reader
import java.nio.FloatBuffer
import java.nio.LongBuffer

// GigaAM v3 ONNX inference — port of GigaAmEngine.cs.
// Inputs:  "features"        [1, N_MEL, T] float32
//          "feature_lengths" [1]           int64
// Output:  logits            [1, T2, vocab_size] float32 (flat-read as T2*vocab_size)
class GigaAmEngine(modelPath: String, vocabReader: Reader) : Closeable {

    private val vocab: Vocab = Vocab.load(vocabReader)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setIntraOpNumThreads(2)
        opts.setInterOpNumThreads(1)
        env.createSession(modelPath, opts)
    }

    // pcm16le — PCM 16 kHz, 16-bit, mono, little-endian (identical format to C# version)
    fun transcribe(pcm: ByteArray): String {
        if (pcm.size < MelSpectrogram.N_FFT * 2) return ""

        val samples = MelSpectrogram.pcmToFloat(pcm)
        val mel = MelSpectrogram.compute(samples)
        val T = mel[0].size
        if (T == 0) return ""

        // Layout [1, N_MEL, T] row-major — matches C# DenseTensor[0, m, t] = mel[m, t]
        val featData = FloatArray(MelSpectrogram.N_MEL * T)
        for (m in 0 until MelSpectrogram.N_MEL)
            for (t in 0 until T)
                featData[m * T + t] = mel[m][t]

        val featTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(featData),
            longArrayOf(1L, MelSpectrogram.N_MEL.toLong(), T.toLong())
        )
        val lenTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(T.toLong())),
            longArrayOf(1L)
        )

        return featTensor.use { feat ->
            lenTensor.use { len ->
                session.run(mapOf("features" to feat, "feature_lengths" to len)).use { results ->
                    val lpTensor = results.iterator().next().value as OnnxTensor
                    val buf = lpTensor.floatBuffer
                    val flat = FloatArray(buf.remaining()).also { buf.get(it) }
                    CtcDecoder.greedyDecode(flat, flat.size / vocab.size, vocab)
                }
            }
        }
    }

    override fun close() = session.close()
}

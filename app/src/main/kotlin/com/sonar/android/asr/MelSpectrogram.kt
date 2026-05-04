package com.sonar.android.asr

import kotlin.math.*

// Mel-spectrogram preprocessing — exact port of GigaAmEngine.cs ComputeMel().
// Parameters must NOT be changed: they are baked into the giga-am-v3 model.
//
// n_fft=320, hop=160, n_mel=64, sr=16000, center=false,
// periodic Hann window, HTK mel scale, ln(max(e, 1e-9)), per-feature normalization.
internal object MelSpectrogram {

    const val N_FFT = 320
    const val HOP   = 160
    const val N_MEL = 64
    const val SR    = 16_000

    private val HALF = N_FFT / 2 + 1

    // Precomputed at class load — same approach as static GigaAmEngine()
    private val hann: FloatArray = FloatArray(N_FFT) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / N_FFT)).toFloat()
    }

    // twCos[k][n] = cos(-2π·k·n / N_FFT)
    private val twCos: Array<FloatArray> = Array(HALF) { k ->
        FloatArray(N_FFT) { n -> cos(-2.0 * PI * k * n / N_FFT).toFloat() }
    }
    private val twSin: Array<FloatArray> = Array(HALF) { k ->
        FloatArray(N_FFT) { n -> sin(-2.0 * PI * k * n / N_FFT).toFloat() }
    }

    // melFb[m][k] — HTK triangular filterbank, no Slaney normalization
    private val melFb: Array<FloatArray> = makeMelFilterbank()

    // ── PCM int16 LE → float32 [-1, 1] ──────────────────────────────────────
    fun pcmToFloat(pcm: ByteArray): FloatArray {
        val f = FloatArray(pcm.size / 2)
        for (i in f.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            f[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return f
    }

    // ── Mel-spectrogram with per-feature normalization ───────────────────────
    // Returns [N_MEL][nFrames]. center=false (no padding, matches C#).
    fun compute(samples: FloatArray): Array<FloatArray> {
        if (samples.size < N_FFT) return Array(N_MEL) { FloatArray(0) }
        val nFrames = (samples.size - N_FFT) / HOP + 1
        if (nFrames <= 0) return Array(N_MEL) { FloatArray(0) }

        val mel      = Array(N_MEL) { FloatArray(nFrames) }
        val power    = FloatArray(HALF)
        val windowed = FloatArray(N_FFT)

        for (frame in 0 until nFrames) {
            val offset = frame * HOP
            for (n in 0 until N_FFT) windowed[n] = samples[offset + n] * hann[n]

            // Manual DFT: N_FFT=320 is not a power of 2, no FFT library needed
            for (k in 0 until HALF) {
                var re = 0f; var im = 0f
                val cosRow = twCos[k]; val sinRow = twSin[k]
                for (n in 0 until N_FFT) {
                    val x = windowed[n]
                    re += x * cosRow[n]
                    im += x * sinRow[n]
                }
                power[k] = re * re + im * im
            }

            for (m in 0 until N_MEL) {
                var e = 0f
                val fb = melFb[m]
                for (k in 0 until HALF) e += fb[k] * power[k]
                mel[m][frame] = ln(maxOf(e, 1e-9f))
            }
        }

        // per-feature normalization (NeMo: normalize=per_feature)
        for (m in 0 until N_MEL) {
            val row = mel[m]
            val mean = row.sum() / nFrames
            var sq = 0f
            for (t in 0 until nFrames) { val d = row[t] - mean; sq += d * d }
            val std    = sqrt(sq / nFrames)
            val invStd = 1f / maxOf(std, 1e-5f)
            for (t in 0 until nFrames) row[t] = (row[t] - mean) * invStd
        }

        return mel
    }

    // ── HTK Mel filterbank ────────────────────────────────────────────────────
    private fun freqToMel(f: Double): Double = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToFreq(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun makeMelFilterbank(): Array<FloatArray> {
        val melMin = freqToMel(0.0)
        val melMax = freqToMel(SR / 2.0)
        val pts = DoubleArray(N_MEL + 2) { i -> melMin + (melMax - melMin) * i / (N_MEL + 1) }

        return Array(N_MEL) { m ->
            val fL = melToFreq(pts[m])
            val fC = melToFreq(pts[m + 1])
            val fR = melToFreq(pts[m + 2])
            FloatArray(HALF) { k ->
                val f = k.toDouble() * SR / N_FFT
                when {
                    f <= fL || f >= fR -> 0f
                    f < fC             -> ((f - fL) / (fC - fL)).toFloat()
                    else               -> ((fR - f) / (fR - fC)).toFloat()
                }
            }
        }
    }
}

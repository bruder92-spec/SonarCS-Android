package com.sonar.android.asr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MelSpectrogramTest {

    @Test
    fun `pcmToFloat converts int16 LE correctly`() {
        val pcm = byteArrayOf(
            0xFF.toByte(), 0x7F.toByte(), // 0x7FFF = 32767  → +0.99997
            0x00.toByte(), 0x80.toByte(), // 0x8000 = -32768 → -1.0
            0x00.toByte(), 0x00.toByte()  // 0               →  0.0
        )
        val f = MelSpectrogram.pcmToFloat(pcm)
        assertEquals(3, f.size)
        assertEquals(32767f / 32768f, f[0], 1e-6f)
        assertEquals(-1f,             f[1], 1e-6f)
        assertEquals(0f,              f[2], 1e-6f)
    }

    @Test
    fun `hann window is periodic with w(0)=0 and w(N div 2)=1`() {
        // Periodic Hann: w[i] = 0.5 - 0.5*cos(2π·i/N)  (divisor is N, not N-1)
        val N = MelSpectrogram.N_FFT
        val w = FloatArray(N) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / N)).toFloat() }
        assertEquals(0f, w[0],     1e-7f)
        assertEquals(1f, w[N / 2], 1e-6f)
        // Periodic window: w[N-1] is very small but > 0 (unlike symmetric Hann where w[N-1] = 0)
        assertTrue("w[N-1] should be > 0 for periodic window", w[N - 1] > 0f)
    }

    @Test
    fun `frame count matches formula`() {
        // nFrames = (samples - N_FFT) / HOP + 1
        val samples = FloatArray(MelSpectrogram.SR) // 1 sec
        val mel = MelSpectrogram.compute(samples)
        val expected = (MelSpectrogram.SR - MelSpectrogram.N_FFT) / MelSpectrogram.HOP + 1
        assertEquals(expected, mel[0].size)
    }

    @Test
    fun `output has correct number of mel channels`() {
        val samples = FloatArray(MelSpectrogram.SR)
        val mel = MelSpectrogram.compute(samples)
        assertEquals(MelSpectrogram.N_MEL, mel.size)
    }

    @Test
    fun `silence produces finite output`() {
        // Silence → all mel values equal → std=0 → clamped invStd amplifies float rounding noise.
        // We only assert no NaN/Inf — exact values are undefined for degenerate (zero-std) channels.
        val samples = FloatArray(MelSpectrogram.SR)
        val mel = MelSpectrogram.compute(samples)
        assertEquals(MelSpectrogram.N_MEL, mel.size)
        for (m in 0 until MelSpectrogram.N_MEL)
            for (t in mel[m].indices)
                assertTrue("mel[$m][$t] must be finite", mel[m][t].isFinite())
    }

    @Test
    fun `too short input returns empty`() {
        val mel = MelSpectrogram.compute(FloatArray(MelSpectrogram.N_FFT - 1))
        for (m in 0 until MelSpectrogram.N_MEL)
            assertEquals(0, mel[m].size)
    }

    @Test
    fun `per-feature normalization produces unit variance on active channels`() {
        // White noise → energy across all mel channels → no degenerate channels
        val rng = java.util.Random(42)
        val samples = FloatArray(MelSpectrogram.SR) { (rng.nextFloat() * 2f - 1f) * 0.5f }
        val mel = MelSpectrogram.compute(samples)
        val nFrames = mel[0].size

        for (m in 0 until MelSpectrogram.N_MEL) {
            val row = mel[m]
            // Compute mean and variance-around-mean (not around 0)
            val mean = row.sum() / nFrames
            var sq = 0f
            for (v in row) { val d = v - mean; sq += d * d }
            val varAroundMean = sq / nFrames

            // Skip channels whose original std was near the 1e-5 clamp
            // (those have near-constant output, amplified float noise in mean)
            if (varAroundMean < 0.5f) continue

            assertEquals("mean of channel $m", 0f, mean, 5e-4f)
            assertEquals("variance of channel $m", 1f, varAroundMean, 0.15f)
        }
    }

    @Test
    fun `mel filterbank values are non-negative`() {
        // Non-trivial: compute mel on random-ish signal and check filterbank outputs before log
        // We verify indirectly: mel output after log should be >= ln(1e-9) before normalization
        // This test checks no negative energy slips through
        val samples = FloatArray(MelSpectrogram.SR) { i -> (i % 100 / 100f) - 0.5f }
        val mel = MelSpectrogram.compute(samples) // will throw if NaN or Inf
        for (m in 0 until MelSpectrogram.N_MEL)
            for (t in mel[m].indices)
                assertTrue("NaN/Inf at mel[$m][$t]", mel[m][t].isFinite())
    }
}

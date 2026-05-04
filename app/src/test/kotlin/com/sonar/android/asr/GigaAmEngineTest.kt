package com.sonar.android.asr

import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

// Integration tests that require the actual model files.
// Tests are SKIPPED (not failed) if model/vocab files are not found.
//
// Set env vars to override paths:
//   SONAR_MODEL_PATH  — path to giga-am-v3.onnx
//   SONAR_VOCAB_PATH  — path to giga-am-v3-vocab.txt
//
// Default: looks in the SonarCS PC project directory.
class GigaAmEngineTest {

    private val modelPath = System.getenv("SONAR_MODEL_PATH")
        ?: "C:\\Users\\Zver\\Documents\\Claude\\SonarCS\\giga-am-v3.onnx"
    private val vocabPath = System.getenv("SONAR_VOCAB_PATH")
        ?: "C:\\Users\\Zver\\Documents\\Claude\\SonarCS\\giga-am-v3-vocab.txt"

    private fun modelAvailable() = File(modelPath).exists() && File(vocabPath).exists()

    // ── Vocab tests (no model needed) ────────────────────────────────────────

    @Test
    fun `vocab loads 257 tokens`() {
        assumeTrue("Vocab file not found: $vocabPath", File(vocabPath).exists())
        val vocab = Vocab.load(File(vocabPath).reader())
        assertEquals(257, vocab.size)
    }

    @Test
    fun `vocab blank token is present`() {
        assumeTrue("Vocab file not found: $vocabPath", File(vocabPath).exists())
        val vocab = Vocab.load(File(vocabPath).reader())
        assertTrue("blankId should be valid index", vocab.blankId in 0 until vocab.size)
        assertEquals("<blk>", File(vocabPath).readLines()
            .firstOrNull { it.contains("<blk>") }
            ?.substringBefore(" ")
            ?: "<blk>")
    }

    @Test
    fun `vocab contains space token`() {
        assumeTrue("Vocab file not found: $vocabPath", File(vocabPath).exists())
        val vocab = Vocab.load(File(vocabPath).reader())
        assertTrue("Vocab should contain space token", vocab.tokens.any { it == " " })
    }

    // ── Engine tests (model required) ────────────────────────────────────────

    @Test
    fun `too short PCM returns empty string without crash`() {
        assumeTrue("Model files not found", modelAvailable())
        GigaAmEngine(modelPath, File(vocabPath).reader()).use { engine ->
            assertEquals("", engine.transcribe(ByteArray(10)))
        }
    }

    @Test
    fun `silence returns empty or whitespace only`() {
        assumeTrue("Model files not found", modelAvailable())
        GigaAmEngine(modelPath, File(vocabPath).reader()).use { engine ->
            val pcm = ByteArray(32000) // 1 sec silence
            val result = engine.transcribe(pcm)
            assertTrue(
                "Silence should produce empty or whitespace, got: '$result'",
                result.isBlank()
            )
        }
    }

    @Test
    fun `output is trimmed and contains no stray word-boundary markers`() {
        assumeTrue("Model files not found", modelAvailable())
        GigaAmEngine(modelPath, File(vocabPath).reader()).use { engine ->
            // Sine tone — model will produce something, just verify cleanup
            val samples = FloatArray(32000) { i -> sin(2.0 * PI * 440.0 * i / 16000).toFloat() }
            val pcm = ByteArray(64000).also { buf ->
                for (i in samples.indices) {
                    val s = (samples[i] * 32767).toInt().toShort()
                    buf[i * 2]     = (s.toInt() and 0xFF).toByte()
                    buf[i * 2 + 1] = (s.toInt() shr 8).toByte()
                }
            }
            val result = engine.transcribe(pcm)
            assertFalse("Output should not contain ▁", result.contains('▁'))
            assertEquals("Output should be trimmed", result, result.trim())
        }
    }

    // ── TODO: reference fixture tests ────────────────────────────────────────
    // Once C# binary dumps are generated, add:
    //
    // @Test fun `transcript matches C# reference for clip_01`() {
    //     assumeTrue("Model files not found", modelAvailable())
    //     val pcm  = File("src/test/resources/clip_01.pcm").readBytes()
    //     val expected = "ожидаемый текст"
    //     GigaAmEngine(modelPath, File(vocabPath).reader()).use { engine ->
    //         assertEquals(expected, engine.transcribe(pcm))
    //     }
    // }
}

package com.sonar.android.asr

import org.junit.Assert.*
import org.junit.Test

class CtcDecoderTest {

    // Minimal 4-token vocab: "а"=0, "б"=1, " "=2, "<blk>"=3
    private val vocab = Vocab(
        tokens  = arrayOf("а", "б", " ", "<blk>"),
        size    = 4,
        blankId = 3
    )

    private fun logits(vararg winners: Int): FloatArray {
        val T = winners.size
        return FloatArray(T * vocab.size).also { arr ->
            for (t in winners.indices) arr[t * vocab.size + winners[t]] = 1f
        }
    }

    @Test
    fun `all blanks produce empty string`() {
        assertEquals("", CtcDecoder.greedyDecode(logits(3, 3, 3), 3, vocab))
    }

    @Test
    fun `repeated token without blank collapses to one`() {
        // "а а а" → "а"
        assertEquals("а", CtcDecoder.greedyDecode(logits(0, 0, 0), 3, vocab))
    }

    @Test
    fun `blank between identical tokens prevents collapse`() {
        // "а blank а" → "аа"
        assertEquals("аа", CtcDecoder.greedyDecode(logits(0, 3, 0), 3, vocab))
    }

    @Test
    fun `space token is included in output`() {
        // "а space б" → "а б"
        assertEquals("а б", CtcDecoder.greedyDecode(logits(0, 2, 1), 3, vocab))
    }

    @Test
    fun `service tokens starting with angle bracket are skipped`() {
        // Token "<blk>" starts with '<', should be treated as blank by collapse logic
        // but also skipped by the '<' guard — test with a hypothetical <unk> token
        val vocabWithUnk = Vocab(
            tokens  = arrayOf("а", "<unk>", "<blk>"),
            size    = 3,
            blankId = 2
        )
        val lp = logits(0, 1, 0).let { orig ->
            // rebuild for 3-token vocab
            FloatArray(3 * 3).also { arr ->
                arr[0 * 3 + 0] = 1f // t=0: "а"
                arr[1 * 3 + 1] = 1f // t=1: "<unk>" — should be skipped
                arr[2 * 3 + 0] = 1f // t=2: "а"
            }
        }
        assertEquals("аа", CtcDecoder.greedyDecode(lp, 3, vocabWithUnk))
    }

    @Test
    fun `leading and trailing spaces are trimmed`() {
        // " а " → "а"
        assertEquals("а", CtcDecoder.greedyDecode(logits(2, 0, 2), 3, vocab))
    }

    @Test
    fun `empty logprobs returns empty string`() {
        assertEquals("", CtcDecoder.greedyDecode(FloatArray(0), 0, vocab))
    }
}

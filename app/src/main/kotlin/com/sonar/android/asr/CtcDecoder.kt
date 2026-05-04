package com.sonar.android.asr

// CTC greedy decoder — exact port of GigaAmEngine.cs CtcGreedyDecode().
// Collapses repeated tokens, removes blanks, skips <...> service tokens.
internal object CtcDecoder {

    fun greedyDecode(logProbs: FloatArray, T: Int, vocab: Vocab): String {
        val sb = StringBuilder()
        var prev = -1

        for (t in 0 until T) {
            var best = 0
            var bestVal = logProbs[t * vocab.size]
            for (c in 1 until vocab.size) {
                val v = logProbs[t * vocab.size + c]
                if (v > bestVal) { bestVal = v; best = c }
            }

            if (best != vocab.blankId && best != prev) {
                val token = vocab.tokens[best]
                if (token.isNotEmpty() && token[0] != '<') sb.append(token)
            }
            prev = best
        }

        // Safety: replace any stray ▁ that slipped through vocab normalization
        return sb.toString().replace('▁', ' ').trim()
    }
}

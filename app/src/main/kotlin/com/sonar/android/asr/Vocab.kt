package com.sonar.android.asr

import java.io.Reader

// Vocabulary loaded from giga-am-v3-vocab.txt.
// Format: "token index" (one pair per line). ▁ (U+2581) is normalized to space.
internal data class Vocab(val tokens: Array<String>, val size: Int, val blankId: Int) {

    companion object {
        fun load(reader: Reader): Vocab {
            val pairs = mutableListOf<Pair<Int, String>>()
            var maxId = 0

            reader.readLines().forEach { line ->
                val s = line.trim()
                if (s.isEmpty()) return@forEach
                val sp = s.lastIndexOf(' ')
                if (sp < 0) return@forEach
                val id = s.substring(sp + 1).toIntOrNull() ?: return@forEach
                val token = s.substring(0, sp)
                pairs += id to token
                if (id > maxId) maxId = id
            }

            val tokens = Array(maxId + 1) { "" }
            var blankId = maxId
            pairs.forEach { (id, token) ->
                // ▁ (U+2581) = word boundary → normalize to regular space
                tokens[id] = if (token == "▁") " " else token
                if (token == "<blk>") blankId = id
            }
            return Vocab(tokens, maxId + 1, blankId)
        }
    }

    // suppress Array<String> equals warning — data class identity is fine for tests
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

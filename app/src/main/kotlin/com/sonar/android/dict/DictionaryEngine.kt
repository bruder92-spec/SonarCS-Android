package com.sonar.android.dict

import android.content.res.AssetManager
import java.io.Reader

// Port of DictionaryEngine.cs. Greedy left-to-right replacement on word boundaries.
// Entries sorted by descending key length so longer matches win.
internal class DictionaryEngine private constructor(
    private val entries: List<Pair<String, String>>
) {

    val isEmpty get() = entries.isEmpty()

    fun apply(text: String): String {
        if (entries.isEmpty() || text.isEmpty()) return text
        val lower = text.lowercase()
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val leftOk = i == 0 || !lower[i - 1].isLetterOrDigit()
            var matched = false
            if (leftOk) {
                for ((key, value) in entries) {
                    val end = i + key.length
                    if (end > lower.length) continue
                    if (lower.substring(i, end) != key) continue
                    val rightOk = end == lower.length || !lower[end].isLetterOrDigit()
                    if (!rightOk) continue
                    sb.append(value)
                    i = end
                    matched = true
                    break
                }
            }
            if (!matched) sb.append(text[i++])
        }
        return sb.toString()
    }

    companion object {

        fun fromAssets(
            assets: AssetManager,
            oilGas: Boolean,
            legal: Boolean,
            economy: Boolean
        ): DictionaryEngine {
            val entries = mutableListOf<Pair<String, String>>()
            if (oilGas)  loadAsset(assets, "dictionary_oil_gas.txt", entries)
            if (legal)   loadAsset(assets, "dictionary_legal.txt", entries)
            if (economy) loadAsset(assets, "dictionary_economy.txt", entries)
            entries.sortByDescending { it.first.length }
            return DictionaryEngine(entries)
        }

        fun fromReaders(vararg readers: Reader): DictionaryEngine {
            val entries = mutableListOf<Pair<String, String>>()
            readers.forEach { loadReader(it, entries) }
            entries.sortByDescending { it.first.length }
            return DictionaryEngine(entries)
        }

        private fun loadAsset(assets: AssetManager, name: String, out: MutableList<Pair<String, String>>) {
            try { assets.open(name).reader().use { loadReader(it, out) } } catch (_: Exception) {}
        }

        private fun loadReader(reader: Reader, out: MutableList<Pair<String, String>>) {
            reader.buffered().forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEachLine
                val eq = trimmed.indexOf('=')
                if (eq < 1) return@forEachLine
                val key = trimmed.substring(0, eq).trim().lowercase()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) out += key to value
            }
        }
    }
}

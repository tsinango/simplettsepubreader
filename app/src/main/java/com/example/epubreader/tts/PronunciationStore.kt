package com.example.epubreader.tts

import android.content.Context

class PronunciationStore(context: Context) {
    private val prefs = context.getSharedPreferences("tts_pronunciation", Context.MODE_PRIVATE)

    fun asEditableText(): String = prefs.getString(KEY_RULES, "").orEmpty()

    fun replacements(): Map<String, String> = parse(asEditableText())

    fun saveEditableText(value: String) {
        val canonical = parse(value).entries.joinToString("\n") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_RULES, canonical).apply()
    }

    companion object {
        private const val KEY_RULES = "rules"

        fun parse(value: String): Map<String, String> = buildMap {
            value.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val source = line.substring(0, separator).trim()
                val spoken = line.substring(separator + 1).trim()
                if (source.isNotEmpty() && spoken.isNotEmpty()) put(source, spoken)
            }
        }
    }
}

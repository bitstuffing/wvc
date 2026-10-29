package com.github.bitstuffing.webvideocaster.adblock

import android.content.Context

data class AdBlockSettings(
    val enabled: Boolean = true,
    val sources: List<String> = DEFAULT_SOURCE
) {
    companion object {
        val DEFAULT_SOURCE = listOf(
            //"https://warui.intaa.net/adhosts/hosts_lb.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&amp;showintro=0&amp;mimetype=plaintext"
        )

        private const val PREFS = "adblock_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SOURCES = "sources"

        fun load(context: Context): AdBlockSettings {
            val prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val sources = prefs.getString(KEY_SOURCES, null)
                ?.lines()
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: listOf(DEFAULT_SOURCE)

            return AdBlockSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                sources = DEFAULT_SOURCE
            )
        }

        fun save(context: Context, settings: AdBlockSettings) {
            context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putString(KEY_SOURCES,settings.sources.distinct().joinToString("\n"))
                .apply()
        }
    }
}
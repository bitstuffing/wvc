package com.github.bitstuffing.webvideocaster.adblock

import android.content.Context
import android.util.Log
import com.github.bitstuffing.webvideocaster.utils.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AdBlockRepository(
    context: Context,
    private val blocker: AdBlocker
) {
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "AdBlockRepository"
        private const val CACHE_PREFS = "adblock_cache"
        private const val KEY_DOMAINS = "domains"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 20_000
    }

    suspend fun initialize(
        settings: AdBlockSettings = AdBlockSettings.load(appContext)
    ) {
        blocker.enabled = settings.enabled
        loadCache()
        refresh(settings)
    }

    suspend fun refresh(
        settings: AdBlockSettings = AdBlockSettings.load(appContext)
    ) = withContext(Dispatchers.IO) {
        blocker.enabled = settings.enabled

        val merged = mutableSetOf<String>()
        var successfulSources = 0

        for (source in settings.sources.distinct()) {
            try {
                val domains = downloadAndParse(source)

                if (domains.isNotEmpty()) {
                    merged.addAll(domains)
                    successfulSources++
                    Log.i(
                        TAG,
                        "List loaded: $source (${domains.size} domains)"
                    )
                } else {
                    Log.w(TAG, "Empty list or without domains: $source")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't download $source", e)
            }
        }

        if (successfulSources > 0) {
            blocker.replaceDomains(merged)

            appContext.getSharedPreferences(
                CACHE_PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .putStringSet(KEY_DOMAINS, HashSet(merged))
                .apply()

            Log.i(TAG, "Block updated: ${merged.size} domains")
        }
    }

    private fun loadCache() {
        val cached = appContext.getSharedPreferences(
            CACHE_PREFS,
            Context.MODE_PRIVATE
        ).getStringSet(KEY_DOMAINS, emptySet())
            .orEmpty()

        if (cached.isNotEmpty()) {
            blocker.replaceDomains(cached)
            Log.i(TAG, "Loaded cache: ${cached.size} domains")
        }
    }

    private fun downloadAndParse(source: String): Set<String> {
        require(source.startsWith("https://", ignoreCase = true)) {
            "Just it's admitted HTTPS sources"
        }
        val connection = URL(source).openConnection()
                as HttpURLConnection

        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()

            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}")
            }

            return connection.inputStream.bufferedReader(Charsets.UTF_8).useLines {
                lines -> lines.mapNotNull(::parseHostLine).toSet()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseHostLine(line: String): String? {
        val clean = line.substringBefore('#').trim()
        if (clean.isEmpty()) return null
        // Format dnsmasq: address=/ads.example.com/127.0.0.1
        if (clean.startsWith("address=/", ignoreCase = true)) {
            val domain = clean.removePrefix("address=")
                .substringAfter("/")
                .substringBefore("/")
                .trim()
            return normalizeDomain(domain)
        }
        // Host and domains list format
        val parts = clean.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && isIpAddress(parts[0]) -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }
        return normalizeDomain(candidate)
    }

    private fun normalizeDomain(candidate: String): String? {
        val domain = candidate.trimEnd('.').lowercase(Locale.ROOT)
        if (domain.isEmpty() || domain.length > 253) return null
        if (domain == "localhost" || domain.endsWith(".localhost")) return null
        if (domain.endsWith(".local")) return null
        if (!domain.contains('.')) return null
        if (isIpAddress(domain)) return null

        val valid = domain.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.firstOrNull()?.isLetterOrDigit() == true &&
                label.lastOrNull()?.isLetterOrDigit() == true &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
        return domain.takeIf { valid }
    }

    private fun isIpAddress(value: String): Boolean {
        if (value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            return value.split('.').all {
                it.toIntOrNull() in 0..255
            }
        }
        return value.contains(':') && value.matches(Regex("[0-9a-fA-F:]+"))
    }

    fun loadCachedDomains() {
        val cached = appContext.getSharedPreferences(CACHE_PREFS,Context.MODE_PRIVATE).getStringSet(KEY_DOMAINS, emptySet()).orEmpty()
        if (cached.isNotEmpty()) {
            blocker.replaceDomains(cached)
            Log.i(TAG, "Loaded cache: ${cached.size} domains")
        }
    }
}
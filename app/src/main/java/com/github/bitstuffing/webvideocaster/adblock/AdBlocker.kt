package com.github.bitstuffing.webvideocaster.adblock

import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class AdBlocker {

    private val blockedHosts = AtomicReference<Set<String>>(emptySet())

    @Volatile
    var enabled: Boolean = true

    val domainCount: Int
        get() = blockedHosts.get().size

    fun replaceDomains(domains: Set<String>) {
        blockedHosts.set(domains)
    }

    fun isBlocked(url: String): Boolean {
        if (!enabled) return false

        val host = try {
            URI(url).host
                ?.trimEnd('.')
                ?.lowercase(Locale.ROOT)
                ?: return false
        } catch (_: Exception) {
            return false
        }

        val domains = blockedHosts.get()
        var candidate = host

        while (candidate.isNotEmpty()) {
            if (candidate in domains) return true

            val dot = candidate.indexOf('.')
            if (dot < 0) break

            candidate = candidate.substring(dot + 1)
        }

        return false
    }
}
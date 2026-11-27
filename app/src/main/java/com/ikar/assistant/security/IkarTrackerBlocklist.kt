package com.ikar.assistant.security

import java.util.Locale

/**
 * Small, local list of well-known third-party analytics and advertising hosts.
 * This is a privacy aid, not a complete tracker database or malware blocklist.
 */
internal object IkarTrackerBlocklist {
    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "connect.facebook.net",
        "hotjar.com",
        "hotjar.io",
        "clarity.ms",
        "bat.bing.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "mixpanel.com",
        "scorecardresearch.com",
        "quantserve.com",
        "ads-twitter.com",
        "analytics.tiktok.com",
        "snap.licdn.com",
        "px.ads.linkedin.com"
    )

    fun isTrackerHost(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() } ?: return false
        return blockedDomains.any { normalized == it || normalized.endsWith(".$it") }
    }
}

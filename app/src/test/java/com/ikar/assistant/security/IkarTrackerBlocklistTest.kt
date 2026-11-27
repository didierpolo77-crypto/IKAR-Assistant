package com.ikar.assistant.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IkarTrackerBlocklistTest {
    @Test
    fun blocksKnownTrackerHostsAndSubdomains() {
        assertTrue(IkarTrackerBlocklist.isTrackerHost("google-analytics.com"))
        assertTrue(IkarTrackerBlocklist.isTrackerHost("www.google-analytics.com"))
        assertTrue(IkarTrackerBlocklist.isTrackerHost("connect.facebook.net"))
        assertTrue(IkarTrackerBlocklist.isTrackerHost("bat.bing.com"))
        assertTrue(IkarTrackerBlocklist.isTrackerHost("px.ads.linkedin.com"))
    }

    @Test
    fun doesNotBlockLookalikeOrUnrelatedHosts() {
        assertFalse(IkarTrackerBlocklist.isTrackerHost("notgoogle-analytics.com"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("google-analytics.com.attacker.test"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("example.org"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("www.bing.com"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("www.linkedin.com"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("static.facebook.com"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost(null))
    }
}

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
    }

    @Test
    fun doesNotBlockLookalikeOrUnrelatedHosts() {
        assertFalse(IkarTrackerBlocklist.isTrackerHost("notgoogle-analytics.com"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("google-analytics.com.attacker.test"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost("example.org"))
        assertFalse(IkarTrackerBlocklist.isTrackerHost(null))
    }
}

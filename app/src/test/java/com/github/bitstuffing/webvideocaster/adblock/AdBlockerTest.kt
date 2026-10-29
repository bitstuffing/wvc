
package com.github.bitstuffing.webvideocaster.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdBlockerTest {

    private lateinit var blocker: AdBlocker

    private val adDomains = setOf(
        "4jnzhl0d0.com",
        "50websads.com",
        "51.la",
        "518ad.com",
        "6sc.co",
        "777partner.com",
        "77tracking.com",
        "7bpeople.com",
        "7cnq.net",
        "82o9v830.com",
        "a-ads.com",
        "a.bestcontentfood.top"
    )

    private val allowedDomains = setOf(
        "google.com",
        "duckduckgo.com",
        "bing.com",
        "facebook.com"
    )

    @Before
    fun setUp() {
        blocker = AdBlocker()
        blocker.replaceDomains(adDomains)
    }

    @Test
    fun blocksKnownAdvertisingDomains() {
        adDomains.forEach { domain ->
            assertTrue(
                "Expected $domain to be blocked",
                blocker.isBlocked("https://$domain/")
            )
        }
    }

    @Test
    fun allowsLegitimateDomains() {
        allowedDomains.forEach { domain ->
            assertFalse(
                "Expected $domain not to be blocked",
                blocker.isBlocked("https://$domain/")
            )
        }
    }

    @Test
    fun blocksSubdomainsOfAdvertisingDomains() {
        assertTrue(
            blocker.isBlocked("https://www.50websads.com/banner.js")
        )
        assertTrue(
            blocker.isBlocked("https://sub.51.la/tracker")
        )
        assertTrue(
            blocker.isBlocked("https://sub.a-ads.com/ad.js")
        )
    }

    @Test
    fun doesNotBlockSimilarButDifferentDomains() {
        assertFalse(
            blocker.isBlocked("https://not50websads.com/")
        )
        assertFalse(
            blocker.isBlocked("https://fake-google.com/")
        )
    }

    @Test
    fun ignoresDomainCase() {
        assertTrue(
            blocker.isBlocked("https://50WEBSADS.COM/")
        )
    }

    @Test
    fun doesNotBlockWhenDisabled() {
        blocker.enabled = false

        assertFalse(
            blocker.isBlocked("https://50websads.com/")
        )
    }

    @Test
    fun doesNotBlockInvalidUrls() {
        assertFalse(blocker.isBlocked("not a URL"))
    }

    @Test
    fun reportsNumberOfDomains() {
        assertEquals(adDomains.size, blocker.domainCount)
    }
}

package org.qbook

import android.content.ContextWrapper
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qbook.utils.BlockList

class BlockListTest {

    private class FakeContext : ContextWrapper(null) {
        override fun getAssets(): AssetManager {
            throw java.io.IOException("Asset not found in unit test environment")
        }
    }

    @Test
    fun `isAllowed correctly identifies allowlisted hosts`() {
        assertTrue(BlockList.isAllowed("facebook.com"))
        assertTrue(BlockList.isAllowed("m.facebook.com"))
        assertTrue(BlockList.isAllowed("sub.fbcdn.net"))
        assertTrue(BlockList.isAllowed("youtube.com"))
        assertFalse(BlockList.isAllowed("doubleclick.net"))
        assertFalse(BlockList.isAllowed("tracker.com"))
    }

    @Test
    fun `normalizeHost cleans up leading www and lowercases`() {
        assertEquals("facebook.com", BlockList.normalizeHost("WWW.FACEBOOK.COM"))
        assertEquals("ads.doubleclick.net", BlockList.normalizeHost("www.ads.doubleclick.net"))
        assertEquals("", BlockList.normalizeHost(null))
        assertEquals("", BlockList.normalizeHost(""))
    }

    @Test
    fun `blocksPath detects tracking and ad path fragments`() {
        assertTrue(BlockList.blocksPath("https://example.com/pagead/script.js"))
        assertTrue(BlockList.blocksPath("https://example.com/adsbygoogle"))
        assertTrue(BlockList.blocksPath("https://example.com/gtag/js"))
        assertFalse(BlockList.blocksPath("https://example.com/profile/picture.png"))
    }

    @Test
    fun `blocksHost checks domain and subdomains zero allocation lookup`() {
        BlockList.load(FakeContext())

        assertTrue(BlockList.isLoaded)

        // Test extraBlocked domains and their subdomains
        assertTrue(BlockList.blocksHost("doubleclick.net"))
        assertTrue(BlockList.blocksHost("sub.doubleclick.net"))
        assertTrue(BlockList.blocksHost("a.b.c.doubleclick.net"))

        assertTrue(BlockList.blocksHost("google-analytics.com"))
        assertTrue(BlockList.blocksHost("ssl.google-analytics.com"))

        // Test unblocked hosts
        assertFalse(BlockList.blocksHost("example.com"))
        assertFalse(BlockList.blocksHost("mywebsite.org"))
        assertFalse(BlockList.blocksHost(""))
    }
}

package org.qbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Guards the asset loader.
 *
 * v3.2.0 shipped a 656k-domain blocklist that never loaded: the file is
 * authored as blocklist.txt.gz, but aapt strips the suffix and applies its own
 * compression, so at runtime the entry is named blocklist.txt and is already
 * decompressed. The loader asked for the .gz name and ran GZIPInputStream over
 * it, so it threw, was swallowed, and every network-level ad got through.
 *
 * These tests pin the behaviour that fixes it: pick whichever name exists, and
 * decide on gzip by sniffing the magic bytes rather than the extension.
 */
class BlockListLoaderTest {

    /** Mirrors the sniffing logic in BlockList.load. */
    private fun readDomains(raw: ByteArray): List<String> {
        val out = ArrayList<String>()
        BufferedInputStream(ByteArrayInputStream(raw), 1024).use { buf ->
            buf.mark(2)
            val b0 = buf.read()
            val b1 = buf.read()
            buf.reset()
            val gzipped = (b0 == 0x1f && b1 == 0x8b)
            val src: InputStream = if (gzipped) GZIPInputStream(buf) else buf
            src.bufferedReader().forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && t[0] != '#') out.add(t)
            }
        }
        return out
    }

    private fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray()) }
        return bos.toByteArray()
    }

    private val sample = "doubleclick.net\nbabu88.com\nkrikya.com\n"

    @Test
    fun `reads a plain asset, which is what aapt actually ships`() {
        val got = readDomains(sample.toByteArray())
        assertEquals(listOf("doubleclick.net", "babu88.com", "krikya.com"), got)
    }

    @Test
    fun `reads a gzipped asset too`() {
        val got = readDomains(gzip(sample))
        assertEquals(listOf("doubleclick.net", "babu88.com", "krikya.com"), got)
    }

    @Test
    fun `skips comments and blank lines`() {
        val got = readDomains("# header\n\nads.example\n\n".toByteArray())
        assertEquals(listOf("ads.example"), got)
    }

    // --- the hashing and lookup the real list relies on ---

    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    private fun lookup(domains: List<String>, host: String): Boolean {
        val arr = domains.map { hash(it) }.toLongArray().apply { sort() }
        if (arr.binarySearch(hash(host)) >= 0) return true
        var i = host.indexOf('.')
        while (i in 0 until host.length - 1) {
            if (arr.binarySearch(hash(host.substring(i + 1))) >= 0) return true
            i = host.indexOf('.', i + 1)
        }
        return false
    }

    @Test
    fun `matches a host and its subdomains`() {
        val d = readDomains(sample.toByteArray())
        assertTrue(lookup(d, "doubleclick.net"))
        assertTrue(lookup(d, "ads.doubleclick.net"))
        assertTrue(lookup(d, "a.b.babu88.com"))
    }

    @Test
    fun `does not match unrelated hosts`() {
        val d = readDomains(sample.toByteArray())
        assertFalse(lookup(d, "facebook.com"))
        assertFalse(lookup(d, "scontent.fdac1-1.fna.fbcdn.net"))
        assertFalse(lookup(d, "notbabu88.com.example.org"))
    }
}

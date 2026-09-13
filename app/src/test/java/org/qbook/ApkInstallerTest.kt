package org.qbook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qbook.utils.ApkInstaller

class ApkInstallerTest {

    @Test
    fun `isSecureUpdateUrl accepts valid https GitHub release URLs`() {
        assertTrue(
            ApkInstaller.isSecureUpdateUrl(
                "https://github.com/SA-SUJON/QBooK/releases/download/v1.0.0/app-release.apk"
            )
        )
        assertTrue(
            ApkInstaller.isSecureUpdateUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-268400/12345/app.apk"
            )
        )
    }

    @Test
    fun `isSecureUpdateUrl rejects http cleartext URLs`() {
        assertFalse(
            ApkInstaller.isSecureUpdateUrl(
                "http://github.com/SA-SUJON/QBooK/releases/download/v1.0.0/app-release.apk"
            )
        )
    }

    @Test
    fun `isSecureUpdateUrl rejects untrusted domains`() {
        assertFalse(
            ApkInstaller.isSecureUpdateUrl("https://evil.com/app-release.apk")
        )
        assertFalse(
            ApkInstaller.isSecureUpdateUrl("https://github.com.evil.com/app.apk")
        )
    }

    @Test
    fun `isSecureUpdateUrl handles null, empty, or malformed URLs`() {
        assertFalse(ApkInstaller.isSecureUpdateUrl(null))
        assertFalse(ApkInstaller.isSecureUpdateUrl(""))
        assertFalse(ApkInstaller.isSecureUpdateUrl("   "))
        assertFalse(ApkInstaller.isSecureUpdateUrl("not_a_url"))
    }
}

package com.kino.csbridge

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CallerCheckTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val uid = 10_123

    private fun install(pkg: String, cert: String) {
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION") signatures = arrayOf(Signature(cert.toByteArray().toHex()))
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test fun `kino with our signature is trusted`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "aa")
        shadowOf(context.packageManager).setPackagesForUid(uid, CallerCheck.KINO_PACKAGE)
        assertTrue(CallerCheck.isKino(context, uid))
    }

    @Test fun `kino package signed by someone else is refused`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "bb")
        shadowOf(context.packageManager).setPackagesForUid(uid, CallerCheck.KINO_PACKAGE)
        assertFalse(CallerCheck.isKino(context, uid))
    }

    @Test fun `another package with our signature is refused`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "aa"); install("evil.app", "aa")
        shadowOf(context.packageManager).setPackagesForUid(uid, "evil.app")
        assertFalse(CallerCheck.isKino(context, uid))
    }

    @Test fun `an unknown uid is refused`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "aa")
        assertFalse(CallerCheck.isKino(context, 4242))
    }

    @Test fun `kino not installed is refused`() {
        install(context.packageName, "aa")
        shadowOf(context.packageManager).setPackagesForUid(uid, CallerCheck.KINO_PACKAGE)
        assertFalse(CallerCheck.isKino(context, uid))
    }

    @Test fun `a calling package is trusted only when it is kino with our signature`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "aa"); install("evil.app", "aa")
        assertTrue(CallerCheck.isKinoPackage(context, CallerCheck.KINO_PACKAGE))
        assertFalse(CallerCheck.isKinoPackage(context, "evil.app"))
        assertFalse(CallerCheck.isKinoPackage(context, null)) // a start not for a result has no calling package
    }

    @Test fun `a calling package that is kino signed by someone else is refused`() {
        install(context.packageName, "aa"); install(CallerCheck.KINO_PACKAGE, "bb")
        assertFalse(CallerCheck.isKinoPackage(context, CallerCheck.KINO_PACKAGE))
    }
}

package com.kino.csbridge.desktop

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The non-UI Android classes the desktop bridge implements for real (plugins call them from their normal code). */
class AndroidShimsTest {
    @Test fun uriSplitsLikeAndroid() {
        val u = Uri.parse("https://user@example.com:8443/a/b%20c/d.mp4?x=1&y=two%20words&x=3#frag")
        assertEquals("https", u.scheme)
        assertEquals("example.com", u.host)
        assertEquals(8443, u.port)
        assertEquals("user", u.userInfo)
        assertEquals("/a/b c/d.mp4", u.path)
        assertEquals(listOf("a", "b c", "d.mp4"), u.pathSegments)
        assertEquals("d.mp4", u.lastPathSegment)
        assertEquals("1", u.getQueryParameter("x"))
        assertEquals(listOf("1", "3"), u.getQueryParameters("x"))
        assertEquals("two words", u.getQueryParameter("y"))
        assertNull(u.getQueryParameter("z"))
        assertEquals(setOf("x", "y"), u.queryParameterNames)
        assertEquals("frag", u.fragment)
    }

    @Test fun uriBuilderEncodes() {
        val u = Uri.parse("https://example.com/search").buildUpon().appendQueryParameter("q", "a b&c").appendQueryParameter("p", "2").build()
        assertEquals("https://example.com/search?q=a%20b%26c&p=2", u.toString())
        assertEquals("a b&c", u.getQueryParameter("q"))
        assertEquals("https://example.com/a/b%20c", Uri.Builder().scheme("https").authority("example.com").appendPath("a").appendPath("b c").build().toString())
    }

    @Test fun uriWithoutHostAndRelative() {
        assertNull(Uri.parse("/just/a/path").host)
        assertTrue(Uri.parse("/just/a/path").isRelative)
        assertEquals("example.com", Uri.parse("//example.com/x").host)
        assertEquals("magnet", Uri.parse("magnet:?xt=urn:btih:abc").scheme)
    }

    @Test fun base64MatchesAndroidFlags() {
        val data = "hello world?>".toByteArray()
        assertEquals("aGVsbG8gd29ybGQ/Pg==\n", Base64.encodeToString(data, Base64.DEFAULT))
        assertEquals("aGVsbG8gd29ybGQ/Pg==", Base64.encodeToString(data, Base64.NO_WRAP))
        assertEquals("aGVsbG8gd29ybGQ_Pg", Base64.encodeToString(data, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE))
        assertEquals("hello world?>", String(Base64.decode("aGVsbG8gd29y\nbGQ/Pg==", Base64.DEFAULT)))
        assertEquals("hello world?>", String(Base64.decode("aGVsbG8gd29ybGQ_Pg", Base64.URL_SAFE)))
        val long = ByteArray(100) { it.toByte() }
        val lines = Base64.encodeToString(long, Base64.DEFAULT).trimEnd('\n').split("\n")
        assertEquals(76, lines[0].length)
        assertEquals(2, lines.size)
    }

    @Test fun handlerRunsDelayedAndHonoursRemoval() {
        val h = Handler(Looper.getMainLooper())
        val ran = AtomicInteger()
        val first = CountDownLatch(1)
        h.postDelayed({ ran.incrementAndGet(); first.countDown() }, 30)
        val removed = Runnable { ran.addAndGet(100) }
        h.postDelayed(removed, 200)
        h.removeCallbacks(removed)
        assertTrue(first.await(3, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, ran.get())
        val onLooper = CountDownLatch(1)
        h.post { if (Looper.myLooper() === Looper.getMainLooper()) onLooper.countDown() }
        assertTrue(onLooper.await(3, TimeUnit.SECONDS))
        assertNull(Looper.myLooper()) // the test thread is not the main looper
    }

    @Test fun handlerDeliversMessages() {
        val got = CountDownLatch(1)
        var seen = ""
        val h = Handler(Looper.getMainLooper(), Handler.Callback { m -> seen = "${m.what}:${m.obj}"; got.countDown(); true })
        h.sendMessage(h.obtainMessage(7, "x"))
        assertTrue(got.await(3, TimeUnit.SECONDS))
        assertEquals("7:x", seen)
    }

    @Test fun bundleKeepsTypes() {
        val b = Bundle().apply { putString("a", "1"); putInt("n", 5) }
        assertEquals("1", b.getString("a"))
        assertEquals(5, b.getInt("n"))
        assertEquals(0, b.getInt("a")) // wrong type reads as the default, like Android
        assertEquals("d", b.getString("missing", "d"))
    }

    @Test fun cookieJarFollowsDomainPathSecureAndExpiry() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.setCookie("https://www.example.com/app/page", "sid=abc; Path=/app; Secure")
        cm.setCookie("https://www.example.com/", "theme=dark")
        cm.setCookie("https://www.example.com/", "wide=1; Domain=.example.com")
        cm.setCookie("https://www.example.com/", "gone=1; Max-Age=0")
        assertEquals("sid=abc; theme=dark; wide=1", cm.getCookie("https://www.example.com/app/x"))
        assertEquals("theme=dark; wide=1", cm.getCookie("https://www.example.com/"))
        assertEquals("wide=1", cm.getCookie("https://api.example.com/")) // host-only cookies stay on their host
        assertEquals("theme=dark; wide=1", cm.getCookie("http://www.example.com/")) // a Secure cookie is not sent over http
        assertNull(cm.getCookie("https://other.org/"))
        cm.seed("https://www.example.com/", mapOf("cf_clearance" to "tok"))
        assertTrue(cm.getCookie("https://www.example.com/")!!.contains("cf_clearance=tok"))
        cm.removeAllCookies(null)
        assertFalse(cm.hasCookies())
    }
}

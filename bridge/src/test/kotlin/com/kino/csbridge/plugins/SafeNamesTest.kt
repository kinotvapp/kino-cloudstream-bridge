package com.kino.csbridge.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeNamesTest {
    @Test fun `ids with traversal or junk are refused`() {
        assertNull(SafeNames.fileName("../../x"))
        assertNull(SafeNames.fileName("cloudstream-${"a".repeat(41)}"))
        assertNull(SafeNames.fileName("nuvio-x"))
        assertNull(SafeNames.fileName("cloudstream-"))
        assertNull(SafeNames.fileName("cloudstream-../x"))
        assertNull(SafeNames.fileName("cloudstream-a/b"))
        assertNull(SafeNames.fileName("cloudstream-A"))
        assertNull(SafeNames.fileName("cloudstream-a.b"))
        assertNull(SafeNames.fileName("cloudstream-a\n"))
        assertNull(SafeNames.fileName("xcloudstream-a"))
        assertEquals("cloudstream-cuevana-ab12cd.cs3", SafeNames.fileName("cloudstream-cuevana-ab12cd"))
        assertEquals("cloudstream-${"a".repeat(40)}.cs3", SafeNames.fileName("cloudstream-${"a".repeat(40)}"))
    }
}

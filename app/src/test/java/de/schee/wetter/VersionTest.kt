package de.schee.wetter

import de.schee.wetter.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun parsesWithAndWithoutV() {
        assertEquals(Version(0, 2, 0), Version.parse("v0.2.0"))
        assertEquals(Version(1, 10, 3), Version.parse("1.10.3"))
        assertNull(Version.parse("v1.2"))
        assertNull(Version.parse("latest"))
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.9")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("0.99.99")!!)
        assertTrue(Version.parse("0.1.1")!! > Version.parse("0.1.0")!!)
        assertEquals(0, Version.parse("v0.1.0")!!.compareTo(Version.parse("0.1.0")!!))
    }
}

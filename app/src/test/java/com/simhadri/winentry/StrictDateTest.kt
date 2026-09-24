package com.simhadri.winentry

import com.simhadri.winentry.utils.StrictDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrictDateTest {

    @Test fun serials() {
        assertEquals("2026-04-30", StrictDate.parse("46142"))
        assertEquals("2026-04-30", StrictDate.parse("46142.0"))
        assertEquals("2026-04-30", StrictDate.parse("46142.75"))
        assertNull(StrictDate.parse("12345"))
        assertNull(StrictDate.parse("99999"))
    }

    @Test fun dayFirstNeverMonthFirst() {
        assertEquals("2026-09-05", StrictDate.parse("05/09/2026"))
        assertEquals("2026-09-05", StrictDate.parse("5/9/26"))
        assertEquals("2026-09-05", StrictDate.parse("05-09-2026"))
        assertEquals("2026-09-05", StrictDate.parse("5.9.2026"))
        assertNull(StrictDate.parse("2/15/2026"))
    }

    @Test fun isoAndMonthNames() {
        assertEquals("2026-09-05", StrictDate.parse("2026-09-05"))
        assertEquals("2026-09-05", StrictDate.parse("2026-9-5"))
        assertEquals("2026-09-05", StrictDate.parse("05-Sep-2026"))
        assertEquals("2026-09-05", StrictDate.parse("5 September 2026"))
    }

    @Test fun invalidIsNullNotToday() {
        assertNull(StrictDate.parse(""))
        assertNull(StrictDate.parse(null))
        assertNull(StrictDate.parse("  "))
        assertNull(StrictDate.parse("abc"))
        assertNull(StrictDate.parse("31/02/2026"))
        assertNull(StrictDate.parse("2026-13-01"))
        assertNull(StrictDate.parse("01/01/1999"))
    }
}

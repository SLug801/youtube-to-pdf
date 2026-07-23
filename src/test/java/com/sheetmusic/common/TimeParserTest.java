package com.sheetmusic.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeParserTest {

    @Test
    void parsesSecondsAndClockFormats() {
        assertEquals(0, TimeParser.parseSeconds(""));
        assertEquals(75, TimeParser.parseSeconds("75"));
        assertEquals(75.5, TimeParser.parseSeconds("75.5"));
        assertEquals(75, TimeParser.parseSeconds("01:15"));
        assertEquals(3750, TimeParser.parseSeconds("1:02:30"));
    }

    @Test
    void rejectsInvalidOrNegativeTimes() {
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parseSeconds("-1"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parseSeconds("1:60"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parseSeconds("1::2"));
        assertThrows(IllegalArgumentException.class, () -> TimeParser.parseSeconds("abc"));
    }

    @Test
    void formatsForDisplay() {
        assertEquals("01:15", TimeParser.formatSeconds(75));
        assertEquals("1:02:30", TimeParser.formatSeconds(3750));
    }
}

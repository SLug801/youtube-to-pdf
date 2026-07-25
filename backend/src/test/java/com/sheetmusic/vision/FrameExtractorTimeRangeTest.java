package com.sheetmusic.vision;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FrameExtractorTimeRangeTest {

    private static final FrameExtractor.RoiConfig ROI =
            FrameExtractor.RoiConfig.defaultConfig();

    @Test
    void acceptsOpenEndedAndBoundedRanges() {
        assertDoesNotThrow(() ->
                new FrameExtractor(ROI, Background.OPAQUE, Motion.CUT, 15, 0));
        assertDoesNotThrow(() ->
                new FrameExtractor(ROI, Background.OPAQUE, Motion.CUT, 15, 285));
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () ->
                new FrameExtractor(ROI, Background.OPAQUE, Motion.CUT, -1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new FrameExtractor(ROI, Background.OPAQUE, Motion.CUT, 30, 30));
        assertThrows(IllegalArgumentException.class, () ->
                new FrameExtractor(ROI, Background.OPAQUE, Motion.CUT, 30, 20));
    }
}

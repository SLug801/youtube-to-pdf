package com.sheetmusic.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

class StreamingRowWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void flushesFullRowsAndPadsOnlyTheLastRemainder() throws Exception {
        FrameExtractor extractor = new FrameExtractor(
                FrameExtractor.RoiConfig.defaultConfig(), Background.OPAQUE, Motion.SCROLL);
        FrameExtractor.StreamingRowWriter writer =
                extractor.new StreamingRowWriter(100, 10, tempDir, null);

        Mat firstSeed = solid(100, 10, 10);
        Mat refreshedSeed = solid(100, 10, 40);
        Mat addition = solid(50, 10, 90);
        List<Path> rows;
        try {
            writer.seed(firstSeed);
            writer.replaceSeed(refreshedSeed);
            writer.append(addition);
            rows = writer.finish();
        } finally {
            firstSeed.release();
            refreshedSeed.release();
            addition.release();
            writer.close();
        }

        assertEquals(2, rows.size());
        Mat first = Imgcodecs.imread(rows.get(0).toString());
        Mat last = Imgcodecs.imread(rows.get(1).toString());
        try {
            assertEquals(100, first.cols());
            assertEquals(10, first.rows());
            assertEquals(100, last.cols());
            assertEquals(10, last.rows());
            assertNear(40, first.get(5, 50)[0]);
            assertNear(90, last.get(5, 10)[0]);
            assertNear(255, last.get(5, 80)[0]);
        } finally {
            first.release();
            last.release();
        }
    }

    private static Mat solid(int width, int height, double value) {
        return new Mat(height, width, CvType.CV_8UC3, new Scalar(value, value, value));
    }

    private static void assertNear(double expected, double actual) {
        assertTrue(Math.abs(expected - actual) <= 3,
                () -> "expected about " + expected + " but was " + actual);
    }
}

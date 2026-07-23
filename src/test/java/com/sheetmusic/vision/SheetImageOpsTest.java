package com.sheetmusic.vision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

class SheetImageOpsTest {

    @BeforeAll
    static void loadOpenCv() {
        Loader.load(opencv_java.class);
    }

    @Test
    void recognizesStaffLinesAndRejectsOrdinaryPanel() {
        SheetImageOps ops = new SheetImageOps(Background.OPAQUE);
        Mat sheet = new Mat(120, 600, CvType.CV_8UC3, new Scalar(255, 255, 255));
        Mat panel = new Mat(120, 600, CvType.CV_8UC3, new Scalar(20, 20, 20));
        try {
            for (int y = 40; y <= 72; y += 8) {
                Imgproc.line(sheet, new Point(20, y), new Point(580, y),
                        new Scalar(0, 0, 0), 2);
            }
            Imgproc.rectangle(panel, new Point(250, 40), new Point(350, 80),
                    new Scalar(255, 255, 255), 2);

            assertTrue(ops.hasSheetStructure(sheet));
            assertFalse(ops.hasSheetStructure(panel));
        } finally {
            sheet.release();
            panel.release();
        }
    }
}

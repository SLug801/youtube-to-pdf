package com.sheetmusic;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/**
 * 마디 번호 OCR을 사용해 보이는 마디 숫자를 추출합니다.
 */
public class MeasureDetector {

    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
    // Tesseract 인스턴스를 매번 생성하지 않고 재사용 (Invalid memory access 방지)
    private static final ITesseract tess;

    static {
        tess = new Tesseract();
        
        // 1. tessdata 폴더가 없으면 자동으로 생성합니다.
        java.io.File tessDataFolder = new java.io.File("tessdata");
        if (!tessDataFolder.exists()) {
            tessDataFolder.mkdirs();
        }

        // 2. 필수 학습 데이터 파일이 있는지 확인합니다.
        java.io.File dataFile = new java.io.File(tessDataFolder, "eng.traineddata");
        if (!dataFile.exists()) {
            System.err.println("====================================================");
            System.err.println("[경고] 'tessdata/eng.traineddata' 파일이 없습니다!");
            System.err.println("이 파일이 없으면 'Invalid memory access' 오류가 발생합니다.");
            System.err.println("====================================================");
        }

        tess.setDatapath(tessDataFolder.getAbsolutePath());
        
        tess.setLanguage("eng");
        tess.setTessVariable("tessedit_char_whitelist", "0123456789 ");
        tess.setPageSegMode(7);
    }

    public static List<Integer> extractVisibleMeasures(Mat frame, FrameExtractor.RoiConfig roi) {
        try {
            int height = frame.rows();
            int width = frame.cols();

            // 탐지 영역 확장: 상단 15% -> 35%로 늘려 해머링/풀링 기호까지 포함
            // 최소 60픽셀은 확보하여 저해상도에서도 기호가 잘리지 않도록 함
            int roiY1 = Math.max(0, (int)(height * roi.topRatio()));
            int roiHeight = (int)(height * (roi.bottomRatio() - roi.topRatio()));
            int roiY2 = Math.min(height, roiY1 + Math.max(60, (int)(roiHeight * 0.35)));
            int roiX1 = Math.max(0, (int)(width * roi.leftRatio()));
            int roiX2 = Math.min(width, (int)(width * roi.rightRatio()));

            if (roiY2 <= roiY1 || roiX2 <= roiX1) {
                return List.of();
            }

            Rect headerRect = new Rect(roiX1, roiY1, roiX2 - roiX1, roiY2 - roiY1);
            Mat headerMat = new Mat(frame, headerRect);

            Mat preprocessed = preprocessHeaderForOcr(headerMat);
            BufferedImage image = matToBufferedImage(preprocessed);
            String ocrResult = doOcr(image);

            headerMat.release();
            preprocessed.release();

            return parseVisibleMeasures(ocrResult);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Mat preprocessHeaderForOcr(Mat headerMat) {
        Mat gray = new Mat();
        Imgproc.cvtColor(headerMat, gray, Imgproc.COLOR_BGR2GRAY);

        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new org.opencv.core.Size(3, 3), 0);

        Mat binary = new Mat();
        Imgproc.threshold(blurred, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

        Mat inverted = new Mat();
        Core.bitwise_not(binary, inverted);

        Mat resized = new Mat();
        Imgproc.resize(inverted, resized, new Size(headerMat.cols() * 2, headerMat.rows() * 2));

        gray.release();
        blurred.release();
        binary.release();

        return resized;
    }

    private static String doOcr(BufferedImage image) {
        try {
            // Tesseract는 스레드 안전하지 않으므로 동기화 필수
            synchronized (tess) {
                String result = tess.doOCR(image);
                return result == null ? "" : result.trim();
            }
        } catch (TesseractException e) {
            return "";
        }
    }

    private static List<Integer> parseVisibleMeasures(String ocrText) {
        List<Integer> measures = new ArrayList<>();
        Matcher matcher = DIGIT_PATTERN.matcher(ocrText);
        while (matcher.find()) {
            try {
                measures.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return measures;
    }

    private static BufferedImage matToBufferedImage(Mat mat) throws Exception {
        MatOfByte buffer = new MatOfByte();
        try {
            Imgcodecs.imencode(".png", mat, buffer);
            try (ByteArrayInputStream input = new ByteArrayInputStream(buffer.toArray())) {
                return ImageIO.read(input);
            }
        } finally {
            buffer.release(); // 네이티브 메모리 명시적 해제
        }
    }

    public static class MeasureSegment {
        private final int x1;
        private final int x2;
        private final Mat binaryImage;

        public MeasureSegment(int x1, int x2, Mat binaryImage) {
            this.x1 = x1;
            this.x2 = x2;
            this.binaryImage = binaryImage;
        }

        public int x1() {
            return x1;
        }

        public int x2() {
            return x2;
        }

        public Mat binaryImage() {
            return binaryImage;
        }

        public void release() {
            if (binaryImage != null) {
                binaryImage.release();
            }
        }
    }

    public static List<MeasureSegment> extractMeasureSegments(Mat frame, FrameExtractor.RoiConfig roi) {
        try {
            int height = frame.rows();
            int width = frame.cols();

            // 세그먼트 추출 영역도 OCR과 동일하게 35%로 확장
            int roiY1 = Math.max(0, (int)(height * roi.topRatio()));
            int roiHeight = (int)(height * (roi.bottomRatio() - roi.topRatio()));
            int roiY2 = Math.min(height, roiY1 + Math.max(60, (int)(roiHeight * 0.35)));
            int roiX1 = Math.max(0, (int)(width * roi.leftRatio()));
            int roiX2 = Math.min(width, (int)(width * roi.rightRatio()));

            if (roiY2 <= roiY1 || roiX2 <= roiX1) {
                return List.of();
            }

            Rect headerRect = new Rect(roiX1, roiY1, roiX2 - roiX1, roiY2 - roiY1);
            Mat headerMat = new Mat(frame, headerRect);
            Mat gray = new Mat();
            Imgproc.cvtColor(headerMat, gray, Imgproc.COLOR_BGR2GRAY);
            Mat binary = new Mat();
            Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

            List<MeasureSegment> segments = detectNumberSegments(binary);

            headerMat.release();
            gray.release();
            binary.release();
            return segments;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<MeasureSegment> detectNumberSegments(Mat binary) {
        int width = binary.cols();
        int height = binary.rows();
        int[] counts = new int[width];

        for (int x = 0; x < width; x++) {
            int count = 0;
            for (int y = 0; y < height; y++) {
                double val = binary.get(y, x)[0];
                if (val > 128) {
                    count++;
                }
            }
            counts[x] = count;
        }

        List<MeasureSegment> segments = new ArrayList<>();
        boolean inSegment = false;
        int startX = 0;
        int threshold = Math.max(1, height / 5);

        for (int x = 0; x < width; x++) {
            if (counts[x] > threshold) {
                if (!inSegment) {
                    inSegment = true;
                    startX = x;
                }
            } else if (inSegment) {
                inSegment = false;
                if (x - startX > 4) {
                    Rect segRect = new Rect(startX, 0, x - startX, height);
                    Mat segBin = new Mat(binary, segRect).clone();
                    segments.add(new MeasureSegment(startX, x, segBin));
                }
            }
        }

        if (inSegment && width - startX > 4) {
            Rect segRect = new Rect(startX, 0, width - startX, height);
            Mat segBin = new Mat(binary, segRect).clone();
            segments.add(new MeasureSegment(startX, width, segBin));
        }

        return segments;
    }

    public static int findOverlapCount(List<MeasureSegment> previous, List<MeasureSegment> current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) {
            return 0;
        }

        int maxOverlap = Math.min(previous.size(), current.size());
        for (int k = maxOverlap; k > 0; k--) {
            boolean matched = true;
            for (int i = 0; i < k; i++) {
                MeasureSegment prevSeg = previous.get(previous.size() - k + i);
                MeasureSegment currSeg = current.get(i);
                if (!segmentsMatch(prevSeg, currSeg)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return k;
            }
        }
        return 0;
    }

    private static boolean segmentsMatch(MeasureSegment a, MeasureSegment b) {
        if (a == null || b == null) {
            return false;
        }

        int minW = Math.min(a.binaryImage().cols(), b.binaryImage().cols());
        int minH = Math.min(a.binaryImage().rows(), b.binaryImage().rows());
        if (minW <= 0 || minH <= 0) {
            return false;
        }

        Rect subRect = new Rect(0, 0, minW, minH);
        Mat subA = new Mat(a.binaryImage(), subRect);
        Mat subB = new Mat(b.binaryImage(), subRect);
        Mat diff = new Mat();
        Core.bitwise_xor(subA, subB, diff);

        int diffCount = Core.countNonZero(diff);
        double ratio = (double) diffCount / (minW * minH);

        diff.release();
        subA.release();
        subB.release();

        if (Math.abs(a.x2() - a.x1() - (b.x2() - b.x1())) > Math.max(a.x2() - a.x1(), b.x2() - b.x1()) * 0.5) {
            return false;
        }

        return ratio < 0.35;
    }
}

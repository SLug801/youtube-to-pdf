package com.sheetmusic;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

/**
 * 영상에서 TAB 악보 영역(ROI)만 비교해서 변경 시 프레임 저장
 */
public class FrameExtractor {

    // JavaCV 네이티브 라이브러리 자동 로드
    static {
        try {
            // 의존성 로딩 순서 명시 (OpenBLAS -> OpenCV)
            Loader.load(opencv_java.class);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[오류] 네이티브 라이브러리를 로드할 수 없습니다.");
            System.err.println("원인: " + e.getMessage());
            System.err.println("해결 방법: pom.xml에 'openblas-platform' 의존성이 있는지 확인하세요.");
            // 로드 실패 시에도 프로그램이 바로 죽지 않고 에러를 전파하도록 함
            throw new RuntimeException("OpenCV/OpenBLAS 라이브러리 로드 실패. " +
                "시스템에 맞는 native binaries가 설치되어 있는지 확인하십시오.", e);
        }
    }

    /**
     * ROI 설정값 (영상 전체 높이/너비 대비 비율, 0.0 ~ 1.0)
     *
     * zzero gu 영상 기준 기본값:
     *   - 악보가 하단 약 30% 영역에 위치
     *   - 좌우는 전체 너비 사용
     *
     * 영상마다 다를 수 있으니 --roi 옵션으로 조정 가능
     */
    public record RoiConfig(
            double topRatio,    // 위에서부터 시작 비율 (예: 0.70 = 상위 70% 지점부터)
            double bottomRatio, // 아래 끝 비율 (예: 1.00 = 맨 아래까지)
            double leftRatio,   // 왼쪽 시작 비율 (예: 0.00 = 맨 왼쪽)
            double rightRatio   // 오른쪽 끝 비율 (예: 1.00 = 맨 오른쪽)
    ) {
        /** 기본값: 하단 30% 전체 너비 */
        public static RoiConfig defaultConfig() {
            return new RoiConfig(0.70, 1.00, 0.00, 1.00);
        }

        /** 문자열 파싱: "0.70,1.00,0.00,1.00" */
        public static RoiConfig parse(String s) {
            String[] parts = s.split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                    "ROI 형식: top,bottom,left,right (예: 0.70,1.00,0.00,1.00)");
            }
            return new RoiConfig(
                Double.parseDouble(parts[0].trim()),
                Double.parseDouble(parts[1].trim()),
                Double.parseDouble(parts[2].trim()),
                Double.parseDouble(parts[3].trim())
            );
        }

        @Override
        public String toString() {
            return String.format("상단%.0f%%~하단%.0f%%, 좌%.0f%%~우%.0f%%",
                topRatio * 100, bottomRatio * 100,
                leftRatio * 100, rightRatio * 100);
        }
    }

    private final double threshold;
    private final RoiConfig roi;

    public FrameExtractor(double threshold, RoiConfig roi) {
        this.threshold = threshold;
        this.roi = roi;
    }

    /**
     * 영상에서 TAB 악보가 바뀔 때마다 전체 프레임(full frame)을 저장
     * (비교는 ROI만, 저장은 전체 화면)
     */
    public List<Path> extract(Path videoPath, Path outDir) throws Exception {
        return extract(videoPath, outDir, null);
    }

    public List<Path> extract(Path videoPath, Path outDir, ProgressLogger logger) throws Exception {
        // Native libraries are auto-loaded by JavaCV
        Files.createDirectories(outDir);

        VideoCapture cap = new VideoCapture(videoPath.toString());
        if (!cap.isOpened()) {
            throw new IllegalStateException("영상 파일을 열 수 없습니다: " + videoPath);
        }

        double fps        = cap.get(Videoio.CAP_PROP_FPS);
        long totalFrames  = (long) cap.get(Videoio.CAP_PROP_FRAME_COUNT);
        int  width        = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int  height       = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        double durationSec = totalFrames / fps;

        // ROI 픽셀 좌표 계산 (안전하게 클램프)
        int roiY1 = (int)Math.max(0, Math.min(height - 1, Math.round(height * roi.topRatio())));
        int roiY2 = (int)Math.max(0, Math.min(height, Math.round(height * roi.bottomRatio())));
        int roiX1 = (int)Math.max(0, Math.min(width - 1, Math.round(width * roi.leftRatio())));
        int roiX2 = (int)Math.max(0, Math.min(width, Math.round(width * roi.rightRatio())));
        int roiW = Math.max(0, roiX2 - roiX1);
        int roiH = Math.max(0, roiY2 - roiY1);
        Rect roiRect = new Rect(roiX1, roiY1, roiW, roiH);

        log(logger, "[분석] 해상도: %dx%d | FPS: %.1f | 길이: %.1f초", width, height, fps, durationSec);
        log(logger, "[ROI]  비교 영역: %s → 픽셀 y=%d~%d, x=%d~%d (w=%d h=%d)", roi, roiY1, roiY2, roiX1, roiX2, roiW, roiH);
        log(logger, "[설정] 유사도 임계값: %.2f", threshold);

        int step = Math.max(1, (int)(fps / Config.FRAME_SAMPLE_RATE));

        List<Path> saved = new ArrayList<>();
        int lastCapturedMaxMeasure = -1;
        List<MeasureDetector.MeasureSegment> prevSegments = null;
        Mat prevRoiFrame = null;
        long frameIdx = 0;
        Mat frame = new Mat();

        long startTime = System.currentTimeMillis();

        while (cap.read(frame)) {
            // 현재 프레임의 ROI 영역만 미리 추출 (비교용)
            Mat currentRoiFrame = new Mat(frame, roiRect);

            if (frameIdx % step == 0) {
                List<Integer> currentMeasures = MeasureDetector.extractVisibleMeasures(frame, roi);
                if (!currentMeasures.isEmpty()) {
                    if (prevSegments != null) {
                        prevSegments.forEach(MeasureDetector.MeasureSegment::release);
                        prevSegments = null;
                    }

                    int currentMin = currentMeasures.get(0);
                    int currentMax = currentMeasures.get(currentMeasures.size() - 1);

                    // 중복 제거 핵심 로직: 
                    // 현재 화면의 '가장 작은 마디 번호'가 이전에 저장한 '가장 큰 마디 번호'보다 클 때만 저장합니다.
                    // 이렇게 하면 이전 화면과 겹치는 마디가 하나도 없을 때만 새 페이지로 저장됩니다.
                    if (lastCapturedMaxMeasure < 0 || currentMin > lastCapturedMaxMeasure) {
                        Path p = saveFrameRoi(frame, roiRect, outDir, saved.size());
                        saved.add(p);
                        lastCapturedMaxMeasure = currentMax;
                        double timeSec = frameIdx / fps;
                        log(logger, "  저장: %s @ %.1f초 (범위:%d~%d)", p.getFileName(), timeSec, currentMin, currentMax);
                    }
                } else {
                    List<MeasureDetector.MeasureSegment> currentSegments = MeasureDetector.extractMeasureSegments(frame, roi);
                    if (currentSegments.isEmpty()) {
                        if (frameIdx % (step * 30) == 0) {
                            log(logger, "  (정보) 마디 블록을 찾지 못했습니다 — 프레임 %d", frameIdx);
                        }

                        // [추가] 마디 숫자를 전혀 못 찾을 경우, 이미지 유사도(Threshold)로 판단
                        if (prevRoiFrame != null) {
                            double sim = computeSimilarity(prevRoiFrame, currentRoiFrame);
                            if (sim < threshold) {
                                Path p = saveFrameRoi(frame, roiRect, outDir, saved.size());
                                saved.add(p);
                                log(logger, "  저장(유사도): %s @ %.1f초 (유사도:%.3f)", p.getFileName(), frameIdx/fps, sim);
                            }
                        }
                    } else {
                        // 숫자를 읽지 못하는 경우(백업 로직)에도 
                        // 겹치는 부분이 아예 없을 때(overlapCount == 0)만 저장하도록 변경합니다.
                        int overlapCount = (prevSegments != null) 
                            ? MeasureDetector.findOverlapCount(prevSegments, currentSegments) 
                            : 0;

                        if (prevSegments == null || overlapCount == 0) {
                            Path p = saveFrameRoi(frame, roiRect, outDir, saved.size());
                            saved.add(p);
                            double timeSec = frameIdx / fps;
                            log(logger, "  저장(백업): %s @ %.1f초 (세그먼트:%d, 겹침:%d)", p.getFileName(), timeSec, currentSegments.size(), overlapCount);
                        }

                        if (prevSegments != null) {
                            prevSegments.forEach(MeasureDetector.MeasureSegment::release);
                        }
                        prevSegments = currentSegments;
                    }
                }

                if (prevRoiFrame != null) prevRoiFrame.release();
                prevRoiFrame = currentRoiFrame.clone();
            }

            currentRoiFrame.release();

            // 진행률 출력 (10초마다)
            if (frameIdx % (int)(fps * 10) == 0 && frameIdx > 0) {
                double pct = (double) frameIdx / totalFrames * 100;
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                log(logger, "  진행: %.0f%% (%d초 경과, 캡처: %d장)", pct, elapsed, saved.size());
            }

                frameIdx++;
        }

        if (prevSegments != null) {
            prevSegments.forEach(MeasureDetector.MeasureSegment::release);
        }

        cap.release();
        frame.release();

        log(logger, "%n[완료] 총 %d장 캡처됨", saved.size());
        return saved;
    }

    public static BufferedImage captureFrame(Path videoPath, double positionRatio, RoiConfig roi) throws Exception {
        // Native libraries are auto-loaded by JavaCV
        VideoCapture cap = new VideoCapture(videoPath.toString());
        if (!cap.isOpened()) {
            throw new IllegalStateException("영상 파일을 열 수 없습니다: " + videoPath);
        }

        try {
            int width = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
            int height = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
            double totalFrames = cap.get(Videoio.CAP_PROP_FRAME_COUNT);
            double targetFrame = Math.max(0, Math.min(totalFrames - 1, totalFrames * positionRatio));
            cap.set(Videoio.CAP_PROP_POS_FRAMES, targetFrame);

            Mat frame = new Mat();
            if (!cap.read(frame) || frame.empty()) {
                frame.release();
                throw new IllegalStateException("프레임을 읽을 수 없습니다: " + videoPath);
            }

            // ROI 부분만 추출 (클램핑 적용)
            int roiY1 = clamp((int)(height * roi.topRatio()), 0, height - 1);
            int roiY2 = clamp((int)(height * roi.bottomRatio()), roiY1 + 1, height);
            int roiX1 = clamp((int)(width  * roi.leftRatio()), 0, width - 1);
            int roiX2 = clamp((int)(width  * roi.rightRatio()), roiX1 + 1, width);
            int roiW = Math.max(1, roiX2 - roiX1);
            int roiH = Math.max(1, roiY2 - roiY1);
            Rect roiRect = new Rect(roiX1, roiY1, roiW, roiH);
            
            Mat roiMat = new Mat(frame, roiRect);
            BufferedImage image = matToBufferedImage(roiMat);
            roiMat.release();
            frame.release();
            return image;
        } finally {
            cap.release();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BufferedImage matToBufferedImage(Mat mat) throws IOException {
        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".png", mat, buffer);
        try (ByteArrayInputStream input = new ByteArrayInputStream(buffer.toArray())) {
            return ImageIO.read(input);
        }
    }

    private static void log(ProgressLogger logger, String format, Object... args) {
        String message = String.format(format, args);
        if (logger != null) {
            logger.log(message);
        } else {
            System.out.println(message);
        }
    }

    /**
     * 두 ROI 이미지의 유사도 계산 (히스토그램 비교, 0~1)
     */
    private double computeSimilarity(Mat img1, Mat img2) {
        Mat gray1 = new Mat();
        Mat gray2 = new Mat();
        Imgproc.cvtColor(img1, gray1, Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(img2, gray2, Imgproc.COLOR_BGR2GRAY);

        Mat hist1 = new Mat();
        Mat hist2 = new Mat();
        MatOfInt histSize = new MatOfInt(256);
        MatOfFloat ranges = new MatOfFloat(0f, 256f);
        MatOfInt channels = new MatOfInt(0);

        Imgproc.calcHist(List.of(gray1), channels, new Mat(), hist1, histSize, ranges);
        Imgproc.calcHist(List.of(gray2), channels, new Mat(), hist2, histSize, ranges);

        Core.normalize(hist1, hist1);
        Core.normalize(hist2, hist2);

        double score = Imgproc.compareHist(hist1, hist2, Imgproc.HISTCMP_CORREL);

        gray1.release(); gray2.release();
        hist1.release(); hist2.release();

        return score;
    }

    private Path saveFrame(Mat frame, Path outDir, int index) throws Exception {
        Path path = outDir.resolve(String.format("frame_%04d.jpg", index));
        Imgcodecs.imwrite(path.toString(), frame);
        return path;
    }

    private Path saveFrameRoi(Mat frame, Rect cropRect, Path outDir, int index) throws Exception {
        // 유효한 캡처 영역인지 확인. 잘못된 경우 전체 프레임으로 대체.
        int frameW = frame.cols();
        int frameH = frame.rows();
        Rect safeRect = cropRect;
        if (cropRect.width <= 0 || cropRect.height <= 0
                || cropRect.x < 0 || cropRect.y < 0
                || cropRect.x + cropRect.width > frameW
                || cropRect.y + cropRect.height > frameH) {
            System.out.println("[경고] 잘못된 캡처 영역이라 전체 프레임을 저장합니다. crop=" + cropRect + " frame=" + frameW + "x" + frameH);
            safeRect = new Rect(0, 0, frameW, frameH);
        }

        Mat roiMat = new Mat(frame, safeRect);
        Path path = outDir.resolve(String.format("frame_%04d.jpg", index));
        Imgcodecs.imwrite(path.toString(), roiMat);
        roiMat.release();
        return path;
    }
}

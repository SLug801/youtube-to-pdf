package com.sheetmusic;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.nio.file.*;
import java.util.*;

/**
 * 영상에서 TAB 악보 영역(ROI)만 비교해서 변경 시 프레임 저장
 */
public class FrameExtractor {

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
        // OpenCV 네이티브 라이브러리 로드
        nu.pattern.OpenCV.loadLocally();

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

        // ROI 픽셀 좌표 계산
        int roiY1 = (int)(height * roi.topRatio());
        int roiY2 = (int)(height * roi.bottomRatio());
        int roiX1 = (int)(width  * roi.leftRatio());
        int roiX2 = (int)(width  * roi.rightRatio());
        Rect roiRect = new Rect(roiX1, roiY1, roiX2 - roiX1, roiY2 - roiY1);

        System.out.printf("[분석] 해상도: %dx%d | FPS: %.1f | 길이: %.1f초%n",
                width, height, fps, durationSec);
        System.out.printf("[ROI]  비교 영역: %s → 픽셀 y=%d~%d, x=%d~%d%n",
                roi, roiY1, roiY2, roiX1, roiX2);
        System.out.printf("[설정] 유사도 임계값: %.2f%n", threshold);

        int step = Math.max(1, (int)(fps / Config.FRAME_SAMPLE_RATE));
        int minFrameGap = (int)(fps * Config.MIN_SECONDS_BETWEEN);

        List<Path> saved = new ArrayList<>();
        Mat prevRoi = null;
        long lastSavedFrameIdx = -9999;
        long frameIdx = 0;
        Mat frame = new Mat();

        long startTime = System.currentTimeMillis();

        while (cap.read(frame)) {
            if (frameIdx % step == 0) {
                Mat currentRoi = new Mat(frame, roiRect);

                if (prevRoi == null) {
                    // 첫 프레임은 무조건 저장
                    Path p = saveFrame(frame, outDir, saved.size());
                    saved.add(p);
                    prevRoi = currentRoi.clone();
                    lastSavedFrameIdx = frameIdx;
                    System.out.printf("  저장: %s (첫 프레임)%n", p.getFileName());

                } else if (frameIdx - lastSavedFrameIdx >= minFrameGap) {
                    double sim = computeSimilarity(prevRoi, currentRoi);

                    if (sim < threshold) {
                        Path p = saveFrame(frame, outDir, saved.size());
                        saved.add(p);
                        prevRoi = currentRoi.clone();
                        lastSavedFrameIdx = frameIdx;
                        double timeSec = frameIdx / fps;
                        System.out.printf("  저장: %s @ %.1f초  (유사도: %.3f)%n",
                                p.getFileName(), timeSec, sim);
                    } else {
                        currentRoi.release();
                    }
                } else {
                    currentRoi.release();
                }
            }

            // 진행률 출력 (10초마다)
            if (frameIdx % (int)(fps * 10) == 0 && frameIdx > 0) {
                double pct = (double) frameIdx / totalFrames * 100;
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("  진행: %.0f%% (%d초 경과, 캡처: %d장)%n",
                        pct, elapsed, saved.size());
            }

            frameIdx++;
        }

        cap.release();
        frame.release();
        if (prevRoi != null) prevRoi.release();

        System.out.printf("%n[완료] 총 %d장 캡처됨%n", saved.size());
        return saved;
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
}

package com.sheetmusic;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

public class FrameExtractor {

    static {
        try {
            Loader.load(opencv_java.class);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("OpenCV 라이브러리 로드 실패", e);
        }
    }

    /** 추출 결과 정보를 담는 레코드 */
    public record ExtractionResult(List<Path> imagePaths, int measuresCaptured) {
    }

    // -------------------------------------------------------------------------
    // 튜닝 파라미터
    // -------------------------------------------------------------------------
    /** 기준 프레임 대비 이 비율 이상 변하면 "스크롤 시작" */
    private static final double CHANGE_THRESHOLD = 0.02;   // 2%

    /** 연속 샘플 간 이 비율 이하면 "멈춤" */
    private static final double STABLE_THRESHOLD = 0.015;  // 1.5%

    /** 몇 번 연속 안정화되면 저장 */
    private static final int STABLE_COUNT_NEEDED = 2; // 더 빠르게 반응하여 마디 누락 방지

    /** diff 이진화 노이즈 제거 기준 */
    private static final int DIFF_BINARIZE = 20;
    // -------------------------------------------------------------------------

    public record RoiConfig(
            double topRatio, double bottomRatio,
            double leftRatio, double rightRatio) {

        public static RoiConfig defaultConfig() {
            return new RoiConfig(0.70, 1.00, 0.00, 1.00);
        }

        public static RoiConfig parse(String s) {
            String[] p = s.split(",");
            if (p.length != 4) throw new IllegalArgumentException("형식: top,bottom,left,right");
            return new RoiConfig(
                Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
                Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
        }

        @Override public String toString() {
            return String.format("상단%.0f%%~하단%.0f%%, 좌%.0f%%~우%.0f%%",
                topRatio*100, bottomRatio*100, leftRatio*100, rightRatio*100);
        }
    }

    private final RoiConfig roi;
    private final int totalMeasures;
    private final double contrast;
    private final int minBrightness;

    public FrameExtractor(double threshold, RoiConfig roi, int totalMeasures) {
        this(threshold, roi, totalMeasures, 1.0, 0);
    }

    public FrameExtractor(double threshold, RoiConfig roi, int totalMeasures, double contrast) {
        this(threshold, roi, totalMeasures, contrast, 0);
    }

    public FrameExtractor(double threshold, RoiConfig roi, int totalMeasures, double contrast, int minBrightness) {
        this.roi = roi;
        this.totalMeasures = totalMeasures;
        this.contrast = contrast;
                this.minBrightness = minBrightness;
    }

    public ExtractionResult extract(Path videoPath, Path outDir) throws Exception {
        return extract(videoPath, outDir, null);
    }

    public ExtractionResult extract(Path videoPath, Path outDir, ProgressLogger logger) throws Exception {
        Files.createDirectories(outDir);

        VideoCapture cap = new VideoCapture(videoPath.toString());

        if (!cap.isOpened()) throw new IllegalStateException("영상 열기 실패: " + videoPath);

        double fps       = cap.get(Videoio.CAP_PROP_FPS);
        long totalFrames = (long) cap.get(Videoio.CAP_PROP_FRAME_COUNT);
        int  width       = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int  height      = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        Rect roiRect     = makeRoiRect(width, height);
        int  step        = Math.max(1, (int)(fps / Config.FRAME_SAMPLE_RATE));

        log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | step=%d",
            width, height, fps, totalFrames / fps, step);
        log(logger, "[파라미터] CHANGE=%.3f STABLE=%.3f COUNT=%d",
            CHANGE_THRESHOLD, STABLE_THRESHOLD, STABLE_COUNT_NEEDED);

        List<Path> saved = new ArrayList<>();

        Mat  lastSavedClean  = null;
        Mat  prevSampleClean = null;
        int  stableCount     = 0;
        int  measuresCaptured = 0;

        Mat frame = new Mat();
        long frameIdx = 0;
        long startMs  = System.currentTimeMillis();

        while (cap.read(frame)) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("취소됨");

            if (frameIdx % step != 0 && frameIdx != totalFrames - 1) {
                frameIdx++;
                continue;
            }

            Mat roiMat     = new Mat(frame, roiRect);
            Mat cleanFrame = preprocess(roiMat);

            // ── 첫 프레임 ──────────────────────────────────────────────────
            if (lastSavedClean == null) {
                log(logger, "[첫 프레임] frame=%d", frameIdx);
                
                // 첫 마디 시작선 찾기
                int startX = findBarLine(frame, roiRect, 10);
                if (startX == -1) startX = 0;
                
                // 첫 프레임은 전체 ROI 저장
                Path p = saveRoi(frame, new Rect(roiRect.x + startX, roiRect.y, roiRect.width - startX, roiRect.height), outDir, saved.size());
                saved.add(p);
                
                int count = countMeasuresInArea(frame, new Rect(roiRect.x + startX, roiRect.y, roiRect.width - startX, roiRect.height));
                measuresCaptured += count;

                log(logger, "FRAME_SAVED:%s", p.toAbsolutePath());

                lastSavedClean  = cleanFrame.clone();
                prevSampleClean = cleanFrame.clone();
                cleanFrame.release();
                roiMat.release();
                log(logger, "[첫 프레임] 저장 완료");
                frameIdx++;
                continue;
            }


            // ── diff 계산 ──────────────────────────────────────────────────
            double diffFromSaved = diffRatio(lastSavedClean, cleanFrame);
            double diffFromPrev  = prevSampleClean != null
                ? diffRatio(prevSampleClean, cleanFrame) : 1.0;

            log(logger, "  [DIFF] frame=%d fromSaved=%.4f fromPrev=%.4f stableCount=%d",
                frameIdx, diffFromSaved, diffFromPrev, stableCount);

            if (diffFromSaved < CHANGE_THRESHOLD) {
                // 변화 없음
                stableCount = 0;
            } else {
                // 변화 있음 → 안정화 체크
                if (diffFromPrev < STABLE_THRESHOLD) {
                    stableCount++;
                    log(logger, "  [STABLE+] stableCount=%d/%d", stableCount, STABLE_COUNT_NEEDED);

                    if (stableCount >= STABLE_COUNT_NEEDED) {
                        // 재생바를 무시하고 실제 악보 선들을 대조하여 겹침 지점 탐색
                        int visualX = findVisualOverlap(lastSavedClean, cleanFrame);
                        
                        // 이전 캡처 지점이 화면의 절반 가까이 이동했을 때만 캡처 (너무 잦은 캡처 방지)
                        if (visualX != -1 && visualX < roiRect.width * 0.5) {
                            int cropX = visualX;
                            int captureWidth = roiRect.width - cropX;
                            
                            // 캡처할 내용이 유의미한 크기일 때
                            if (captureWidth > 100) {
                            Rect captureRect = new Rect(roiRect.x + cropX, roiRect.y, captureWidth, roiRect.height);
                            
                            Path p = saveRoi(frame, captureRect, outDir, saved.size());

                            saved.add(p);
                            
                            int count = countMeasuresInArea(frame, captureRect);
                            measuresCaptured += count;

                            log(logger, "FRAME_SAVED:%s", p.toAbsolutePath());
                            log(logger, "  [%d마디 추가] %s (누적: %d/%d)", count, p.getFileName(), measuresCaptured, totalMeasures);

                            if (lastSavedClean != null) lastSavedClean.release();
                            lastSavedClean = cleanFrame.clone();
                            
                            stableCount = 0; 
                            if (totalMeasures > 0 && measuresCaptured >= totalMeasures) {
                                    log(logger, "[목표 도달] %d마디 추출 완료.", measuresCaptured);
                                break; 
                            }
                        }
                        }
                    }
                } else {
                    stableCount = 0;
                    log(logger, "  [SCROLLING] 아직 움직임 중");
                }
            }

            if (prevSampleClean != null) prevSampleClean.release();
            roiMat.release();
            prevSampleClean = cleanFrame.clone(); // 얕은 복사로 인한 오류 방지
            cleanFrame.release();

            // 진행 로그
            if (frameIdx % (long)(fps * 10) == 0 && frameIdx > 0) {
                double pct   = (double) frameIdx / totalFrames * 100;
                long elapsed = (System.currentTimeMillis() - startMs) / 1000;
                log(logger, "  진행: %.0f%% (%d초 경과, 캡처: %d장)", pct, elapsed, saved.size());
            }

            frameIdx++;
        }

        // ── 마지막 잔여 마디 처리 (Flush) ──
        if (lastSavedClean != null && !frame.empty()) {
            Mat roiMat = new Mat(frame, roiRect);
            int visualX = findVisualOverlap(lastSavedClean, preprocess(roiMat));
            int startX = (visualX != -1) ? visualX : 0;
            if (roiRect.width - startX > 50) {
                Rect lastRect = new Rect(roiRect.x + startX, roiRect.y, roiRect.width - startX, roiRect.height);
                Path p = saveRoi(frame, lastRect, outDir, saved.size());
                saved.add(p);
                measuresCaptured += countMeasuresInArea(frame, lastRect);
            }
            roiMat.release();
        }

        // 정리
        if (lastSavedClean  != null) lastSavedClean.release();
        if (prevSampleClean != null) prevSampleClean.release();
        frame.release();
        cap.release();

        log(logger, "[완료] 총 %d장 캡처됨", saved.size());
        return new ExtractionResult(saved, measuresCaptured);
    }

    /**
     * 영역 내의 세로선(Bar line) 개수를 세어 마디 수를 반환합니다.
     */
    private int countMeasuresInArea(Mat frame, Rect area) {
        try {
            Mat roi = new Mat(frame, area);
            Mat gray = new Mat();
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, gray, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

            // 세로선 탐지 (검은 픽셀이 수직으로 80% 이상인 열)
            int barCount = 0;
            for (int x = 2; x < gray.cols() - 2; x++) {
                int blackPixels = 0;
                for (int y = 0; y < gray.rows(); y++) {
                    if (gray.get(y, x)[0] > 0) blackPixels++;
                }
                if ((double) blackPixels / gray.rows() > 0.8) {
                    barCount++;
                    x += 15; // 마디 선 두께 및 인접 노이즈 건너뛰기
                }
            }
            gray.release(); roi.release();
            return Math.max(1, barCount); // 선이 하나라도 있으면 최소 1마디 이상
        } catch (Exception e) { return 2; } // 실패 시 기본값
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    /**
     * 이전 프레임(oldImg)의 오른쪽 끝 부분이 현재 프레임(newImg)의 어디에 있는지 찾아
     * 중복되는 지점(X 좌표)을 반환합니다.
     */
    private int findVisualOverlap(Mat oldImg, Mat newImg) {
        if (oldImg == null || newImg == null) return -1;
        int w = oldImg.cols();
        int h = oldImg.rows();
        
        // 재생바 간섭을 피하기 위해 오른쪽 끝에서 50px 떨어진 곳을 비교
        int stripW = 80;
        int offset = 50;
        if (w <= stripW + offset) return -1;
        Mat strip = new Mat(oldImg, new Rect(w - stripW - offset, 0, stripW, h));
        
        Mat result = new Mat();
        Imgproc.matchTemplate(newImg, strip, result, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
        
        strip.release();
        result.release();

        if (mmr.maxVal > 0.7) {
            return (int) mmr.maxLoc.x + stripW + offset;
        }
        return -1;
    }

    /**
     * 특정 X 좌표 근처에서 실제 악보의 세로선(Bar line)을 찾습니다.
     */
    private int findBarLine(Mat frame, Rect roiRect, int targetX) {
        try {
            Mat roi = new Mat(frame, roiRect);
            Mat gray = new Mat();
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(gray, gray, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

            int range = 60; // 탐색 범위 확대
            int startX = Math.max(0, targetX - range);
            int endX = Math.min(gray.cols() - 1, targetX + range);

            int bestX = -1;
            double maxVerticality = 0;

            for (int x = endX; x >= startX; x--) { // 마디 번호와 가까운 쪽(오른쪽)부터 탐색
                int blackPixels = 0;
                for (int y = 0; y < gray.rows(); y++) {
                    if (gray.get(y, x)[0] > 0) blackPixels++;
                }
                // 수직으로 꽉 찬 선일수록 세로줄일 확률이 높음
                double verticality = (double) blackPixels / gray.rows();
                if (verticality > 0.7 && verticality > maxVerticality) {
                    maxVerticality = verticality;
                    bestX = x;
                }
            }
            gray.release();
            roi.release();
            return bestX;
        } catch (Exception e) {
            return -1;
        }
    }

    private Mat preprocess(Mat src) {
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        // 사용자가 설정한 명도 이하는 검정색(0)으로 처리 (0-100 범위를 0-255로 변환)
        if (minBrightness > 0) {
            Imgproc.threshold(gray, gray, minBrightness * 2.55, 255, Imgproc.THRESH_TOZERO);
        }

        Imgproc.threshold(gray, gray, 200, 255, Imgproc.THRESH_BINARY);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat opened = new Mat();
        Imgproc.morphologyEx(gray, opened, Imgproc.MORPH_OPEN, kernel);
        gray.release(); kernel.release();
        return opened;
    }

    private double diffRatio(Mat a, Mat b) {
        Mat diff = new Mat();
        Core.absdiff(a, b, diff);
        Imgproc.threshold(diff, diff, DIFF_BINARIZE, 255, Imgproc.THRESH_BINARY);
        int changed = Core.countNonZero(diff);
        diff.release();
        return (double) changed / (a.cols() * a.rows());
    }

    private Path saveRoi(Mat frame, Rect cropRect, Path outDir, int index) throws Exception {
        int fw = frame.cols(), fh = frame.rows();
        boolean valid = cropRect.width > 0 && cropRect.height > 0
            && cropRect.x >= 0 && cropRect.y >= 0
            && cropRect.x + cropRect.width  <= fw
            && cropRect.y + cropRect.height <= fh;
        Rect safe = valid ? cropRect : new Rect(0, 0, fw, fh);
        Mat  out  = new Mat(frame, safe);
        Path path = outDir.resolve(String.format("frame_%04d.jpg", index));
        Imgcodecs.imwrite(path.toString(), out);
        out.release();
        return path;
    }

    private Rect makeRoiRect(int w, int h) {
        int y1 = clamp((int)(h * roi.topRatio()),    0, h - 1);
        int y2 = clamp((int)(h * roi.bottomRatio()), y1 + 1, h);
        int x1 = clamp((int)(w * roi.leftRatio()),   0, w - 1);
        int x2 = clamp((int)(w * roi.rightRatio()),  x1 + 1, w);
        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public static BufferedImage captureFrame(Path videoPath, double positionRatio, RoiConfig roi) throws Exception {
        VideoCapture cap = new VideoCapture(videoPath.toString());
        if (!cap.isOpened()) throw new IllegalStateException("영상 열기 실패: " + videoPath);
        try {
            int w = (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH);
            int h = (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
            double total = cap.get(Videoio.CAP_PROP_FRAME_COUNT);
            cap.set(Videoio.CAP_PROP_POS_FRAMES, Math.max(0, Math.min(total - 1, total * positionRatio)));
            Mat frame = new Mat();
            if (!cap.read(frame) || frame.empty()) throw new IllegalStateException("프레임 읽기 실패");
            int y1 = clamp((int)(h * roi.topRatio()),    0, h - 1);
            int y2 = clamp((int)(h * roi.bottomRatio()), y1 + 1, h);
            int x1 = clamp((int)(w * roi.leftRatio()),   0, w - 1);
            int x2 = clamp((int)(w * roi.rightRatio()),  x1 + 1, w);
            Mat r = new Mat(frame, new Rect(x1, y1, x2 - x1, y2 - y1));
            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".png", r, buf);
            BufferedImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(buf.toArray())) {
                img = ImageIO.read(in);
            }
            r.release(); frame.release();
            return img;
        } finally { cap.release(); }
    }

    private static void log(ProgressLogger logger, String fmt, Object... args) {
        String msg = String.format(fmt, args);
        if (logger != null) logger.log(msg); else System.out.println(msg);
    }
}

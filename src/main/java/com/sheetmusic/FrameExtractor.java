package com.sheetmusic;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * 가로로 스크롤되는 TAB 악보 영상을 한 장의 긴 파노라마로 재구성한 뒤
 * 줄 단위로 잘라 PDF용 이미지로 저장한다.
 *
 * 영상 형태: 악보는 대부분 정지(재생 커서만 이동)하다가 줄 끝에서 스크롤되는 방식.
 * 따라서 "정지 구간은 무시하고, 실제 스크롤이 감지될 때만" 새 콘텐츠를 이어붙인다.
 *
 * 방식: 앵커 기반 슬릿스캔.
 *  - 매 프레임을 "이미 쌓인 파노라마 꼬리"에 정렬(anchor)해 스크롤량 dx를 구한다.
 *    → 누적 오차가 쌓이지 않고, 한 번 틀려도 다음 프레임이 자동 보정.
 *  - dx≈0(정지)이면 아무것도 붙이지 않는다. 실제 스크롤(dx>0, 신뢰도 충분)일 때만 붙인다.
 *  - 특징은 Sobel-X(수직 에지): 가로 오선과 매끄러운 반투명 배경을 자연히 억제하고
 *    마디선·숫자·기둥 같은 세로 획만 남겨 매칭 신뢰도를 높인다.
 */
public class FrameExtractor {

    static {
        try {
            Loader.load(opencv_java.class);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("OpenCV 라이브러리 로드 실패", e);
        }
    }

    // ── 스캔/스티칭 파라미터 ────────────────────────────────────────────────
    private static final int    SCAN_FPS     = 10;      // 초당 검사 프레임
    private static final double SLIT_RATIO   = 0.85;    // 슬릿 컬럼 위치(ROI 폭 대비)
    private static final double TPL_RATIO    = 0.35;    // 매칭 템플릿 폭(ROI 폭 대비)
    private static final double MIN_SCORE    = 0.22;    // 매칭 신뢰도 하한(실제 스크롤 누락 방지)
    private static final int    MIN_SHIFT    = 3;       // 이보다 작은 dx는 정지로 간주
    private static final double CONTENT_MIN  = 0.004;   // 인트로(빈 화면) 판별 임계
    private static final double MAX_DX_RATIO = 0.75;    // 프레임당 최대 스크롤(점프 차단)

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

    public FrameExtractor(RoiConfig roi) {
        this.roi = roi;
    }

    public List<Path> extract(Path videoPath, Path outDir) throws Exception {
        return extract(videoPath, outDir, null);
    }

    public List<Path> extract(Path videoPath, Path outDir, ProgressLogger logger) throws Exception {
        Files.createDirectories(outDir);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();

            double fps      = grabber.getFrameRate();
            long   lengthUs = grabber.getLengthInTime();
            if (lengthUs <= 0) {
                int totalFrames = grabber.getLengthInFrames();
                lengthUs = (fps > 0 && totalFrames > 0)
                    ? (long)(totalFrames / fps * 1_000_000L) : 0;
            }
            double durationSec = lengthUs / 1_000_000.0;
            int    width   = grabber.getImageWidth();
            int    height  = grabber.getImageHeight();
            Rect   roiRect = makeRoiRect(width, height);

            int roiW    = roiRect.width;
            int roiH    = roiRect.height;
            int slitCol = clamp((int)(roiW * SLIT_RATIO), 2, roiW - 1);
            int tw      = clamp((int)(roiW * TPL_RATIO), 8, slitCol - 1);
            int tx      = (roiW - tw) / 2;
            int maxDx   = (int)(roiW * MAX_DX_RATIO);

            long stepUs = (long)(1_000_000.0 / SCAN_FPS);

            log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | 모드=앵커슬릿스캔(%dfps)",
                width, height, fps, durationSec, SCAN_FPS);
            log(logger, "[설정] ROI=%s | 슬릿=%dpx | 템플릿=%dpx@%d", roi, slitCol, tw, tx);

            List<Mat> colorStrips = new ArrayList<>();
            Mat   featTail     = null;    // 파노라마 꼬리의 특징 이미지(최근 roiW 컬럼)
            Mat   lastRoiColor = null;    // 마지막 ROI 컬러(꼬리 처리용)
            int   canvasW      = 0;       // 누적 파노라마 폭
            boolean started    = false;
            int   scrollCnt = 0, staticCnt = 0;   // 스크롤 적용 / 정지 무시 횟수

            Java2DFrameConverter converter = new Java2DFrameConverter();
            long currentUs = 0, sampleIdx = 0;
            long startMs   = System.currentTimeMillis();
            int  lastDx = 0; double lastScore = 0;

            while (lengthUs <= 0 || currentUs <= lengthUs) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("취소됨");

                grabber.setTimestamp(currentUs);
                Frame videoFrame = grabber.grabImage();
                if (videoFrame == null) break;

                Mat frame = frameToMat(videoFrame, converter);
                if (frame.empty()) { currentUs += stepUs; sampleIdx++; continue; }

                Mat roiColor = new Mat(frame, roiRect).clone();
                frame.release();
                Mat feat = featureImage(roiColor);

                if (!started) {
                    double cr = (double) Core.countNonZero(feat) / feat.total();
                    if (cr < CONTENT_MIN) {                 // 인트로 스킵
                        feat.release(); roiColor.release();
                        currentUs += stepUs; sampleIdx++;
                        continue;
                    }
                    // 첫 콘텐츠 프레임: 슬릿 왼쪽 [0..slitCol]을 시드로
                    colorStrips.add(new Mat(roiColor, new Rect(0, 0, slitCol, roiH)).clone());
                    featTail     = new Mat(feat, new Rect(0, 0, slitCol, roiH)).clone();
                    canvasW      = slitCol;
                    lastRoiColor = roiColor;
                    started      = true;
                    feat.release();
                    log(logger, "[콘텐츠 시작] t=%.1fs (시드 %dpx)", currentUs / 1_000_000.0, slitCol);
                    currentUs += stepUs; sampleIdx++;
                    continue;
                }

                // ── 앵커 매칭: 현재 프레임 중앙 밴드를 파노라마 꼬리에 정렬 ─────
                int    fw   = featTail.cols();
                Mat    tpl  = new Mat(feat, new Rect(tx, 0, tw, roiH));
                Mat    res  = new Mat();
                Imgproc.matchTemplate(featTail, tpl, res, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mmr = Core.minMaxLoc(res);
                tpl.release(); res.release();

                double score = mmr.maxVal;
                int    loc   = (int) mmr.maxLoc.x;
                // dx 유도: canvasW가 상쇄되어 featTail 기준 상대식만 남음
                int    dxMeasured = (slitCol - fw) + loc - tx;

                // 실제 스크롤만 채택: 신뢰도 충분 + dx가 유효 범위.
                // dx≈0(정지) 또는 불확실 → 아무것도 붙이지 않음(예측 대체 없음).
                boolean ok = score >= MIN_SCORE
                          && dxMeasured >= MIN_SHIFT
                          && dxMeasured <= maxDx;

                int dxUse = ok ? dxMeasured : 0;
                lastDx = dxUse; lastScore = score;

                if (ok) scrollCnt++; else staticCnt++;

                if (dxUse > 0) {
                    int appendStart = slitCol - dxUse;       // 슬릿 기준 새 콘텐츠 시작
                    Rect strip = new Rect(appendStart, 0, dxUse, roiH);
                    colorStrips.add(new Mat(roiColor, strip).clone());

                    // featTail 갱신: 새 특징을 붙이고 최근 roiW 컬럼만 유지
                    Mat newFeat = new Mat(feat, strip);
                    Mat comb = new Mat();
                    Core.hconcat(Arrays.asList(featTail, newFeat), comb);
                    featTail.release();
                    int cw = comb.cols();
                    if (cw > roiW) {
                        featTail = new Mat(comb, new Rect(cw - roiW, 0, roiW, roiH)).clone();
                        comb.release();
                    } else {
                        featTail = comb;
                    }
                    canvasW += dxUse;
                }

                feat.release();
                lastRoiColor.release(); lastRoiColor = roiColor;

                if (sampleIdx > 0 && sampleIdx % (SCAN_FPS * 8) == 0) {
                    double pct     = lengthUs > 0 ? (double) currentUs / lengthUs * 100 : -1;
                    long   elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log(logger, "  진행 %.0f%% (%ds) 폭=%dpx | dx=%d score=%.2f | 스크롤%d 정지%d",
                        Math.max(pct, 0), elapsed, canvasW, lastDx, lastScore,
                        scrollCnt, staticCnt);
                }

                currentUs += stepUs;
                sampleIdx++;
            }

            // ── 꼬리: 마지막 프레임의 슬릿 오른쪽 [slitCol..roiW] 추가 ────────
            if (lastRoiColor != null && slitCol < roiW) {
                colorStrips.add(new Mat(lastRoiColor, new Rect(slitCol, 0, roiW - slitCol, roiH)).clone());
            }
            if (featTail     != null) featTail.release();
            if (lastRoiColor != null) lastRoiColor.release();

            if (colorStrips.isEmpty()) {
                log(logger, "[경고] 콘텐츠를 찾지 못했습니다.");
                return new ArrayList<>();
            }

            Mat panorama = new Mat();
            Core.hconcat(colorStrips, panorama);
            for (Mat s : colorStrips) s.release();
            log(logger, "[파노라마] 폭=%dpx 높이=%dpx | 스크롤%d 정지%d",
                panorama.cols(), panorama.rows(), scrollCnt, staticCnt);

            List<Path> saved = sliceAndSave(panorama, roiW, outDir, logger);
            panorama.release();
            log(logger, "[완료] 총 %d줄 생성", saved.size());
            return saved;
        }
    }

    /**
     * 매칭/콘텐츠 판별용 특징 이미지.
     * adaptiveThreshold로 TAB 표기(숫자·마디선)를 흰색으로 추출 — 움직이는 반투명 배경의
     * 밝기 변화에 강하고, 글리프 전체가 남아 스크롤 매칭이 잘 잡힌다(Sobel보다 밀도 높음).
     * 가로 오선과 잡티는 제거해 세로 특징 위주로 남긴다.
     */
    private Mat featureImage(Mat roiColor) {
        Mat gray = new Mat();
        Imgproc.cvtColor(roiColor, gray, Imgproc.COLOR_BGR2GRAY);
        // 어두운 배경이면 반전 → 표기를 항상 "밝은 배경 위 어두운 선"으로 정규화
        if (Core.mean(gray).val[0] < 100) Core.bitwise_not(gray, gray);

        Mat bin = new Mat();
        Imgproc.adaptiveThreshold(gray, bin, 255,
            Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 15, 10);
        gray.release();

        // 긴 가로 오선 제거
        int kw = Math.max(15, bin.cols() / 3);
        Mat hk    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kw, 1));
        Mat lines = new Mat();
        Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_OPEN, hk);
        Core.subtract(bin, lines, bin);
        hk.release(); lines.release();

        // 잡티(작은 점) 제거
        Mat dk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, dk);
        dk.release();
        return bin;
    }

    /** 파노라마를 chunkW 단위로 잘라 PDF용 이미지로 저장. 마지막 좁은 조각은 흰 여백으로 패딩. */
    private List<Path> sliceAndSave(Mat panorama, int chunkW, Path outDir, ProgressLogger logger)
            throws Exception {
        int Pw = panorama.cols(), Ph = panorama.rows();
        List<Path> saved = new ArrayList<>();
        int idx = 0;

        for (int x = 0; x < Pw; x += chunkW) {
            int w = Math.min(chunkW, Pw - x);
            if (w < 40) break;

            Mat chunk = new Mat(panorama, new Rect(x, 0, w, Ph));
            Mat out   = maybeInvert(chunk);

            if (w < chunkW) {                          // 마지막 조각 패딩(과대 확대 방지)
                Mat padded = new Mat(Ph, chunkW, out.type(), new Scalar(255, 255, 255));
                out.copyTo(padded.submat(new Rect(0, 0, w, Ph)));
                out.release();
                out = padded;
            }

            Path p = outDir.resolve(String.format("frame_%04d.jpg", idx++));
            Imgcodecs.imwrite(p.toString(), out);
            out.release();
            saved.add(p);
            log(logger, "FRAME_SAVED:%s", p.toAbsolutePath());
        }
        return saved;
    }

    /** 어두운 배경 영상이면 색 반전(흰 배경+검정 내용), 아니면 복사본 반환. */
    private Mat maybeInvert(Mat src) {
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
        double meanBrightness = Core.mean(gray).val[0];
        gray.release();

        Mat out = new Mat();
        if (meanBrightness < 100) Core.bitwise_not(src, out);
        else                      src.copyTo(out);
        return out;
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private static Mat frameToMat(Frame frame, Java2DFrameConverter converter) {
        BufferedImage bimg = converter.convert(frame);
        if (bimg == null) return new Mat();

        if (bimg.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage bgr = new BufferedImage(
                bimg.getWidth(), bimg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g2 = bgr.createGraphics();
            g2.drawImage(bimg, 0, 0, null);
            g2.dispose();
            bimg = bgr;
        }

        byte[] data = ((DataBufferByte) bimg.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(bimg.getHeight(), bimg.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
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
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();

            long lengthUs = grabber.getLengthInTime();
            if (lengthUs <= 0) {
                int totalFrames = grabber.getLengthInFrames();
                double fps = grabber.getFrameRate();
                lengthUs = (fps > 0 && totalFrames > 0)
                    ? (long)(totalFrames / fps * 1_000_000L) : 30_000_000L;
            }
            long posUs = (long)(lengthUs * positionRatio);
            grabber.setTimestamp(posUs);
            Frame videoFrame = grabber.grabImage();
            if (videoFrame == null) throw new IllegalStateException("프레임 읽기 실패");

            Mat frame = frameToMat(videoFrame, new Java2DFrameConverter());

            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".png", frame, buf);
            BufferedImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(buf.toArray())) {
                img = ImageIO.read(in);
            }
            frame.release();
            return img;
        }
    }

    private static void log(ProgressLogger logger, String fmt, Object... args) {
        String msg = String.format(fmt, args);
        if (logger != null) logger.log(msg); else System.out.println(msg);
    }
}
